/*
 * Copyright 2012 Esri.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.esri.gpt.control.webharvest.engine;

import com.esri.gpt.agp.client.AgpItem;
import com.esri.gpt.agp.sync.AgpDestination;
import com.esri.gpt.agp.sync.AgpPush;
import com.esri.gpt.agp.sync.AgpSource;
import com.esri.gpt.catalog.harvest.protocols.HarvestProtocolAgp2Agp;
import com.esri.gpt.catalog.harvest.repository.HrUpdateLastSyncDate;
import com.esri.gpt.control.webharvest.protocol.Protocol;
import com.esri.gpt.framework.context.RequestContext;
import com.esri.gpt.framework.resource.query.Result;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Agp2Agp executor.
 */
abstract class Agp2AgpExecutor extends Executor {

  /**
   * logger
   */
  private static final Logger LOGGER = Logger.getLogger(Agp2AgpExecutor.class.getCanonicalName());

  public Agp2AgpExecutor(DataProcessor dataProcessor, ExecutionUnit unit) {
    super(dataProcessor, unit);
  }

  @Override
  public void execute() {
    RequestContext context = RequestContext.extract(null);

    boolean success = false;
    long count = 0;
    Result result = null;
    final ExecutionUnit unit = getExecutionUnit();
    LOGGER.log(Level.FINEST, "[SYNCHRONIZER] Starting pushing through unit: {0}", unit);
    if (isActive()) {
      getProcessor().onStart(getExecutionUnit());
    }

    ExecutionUnitHelper helper = new ExecutionUnitHelper(getExecutionUnit());
    // get report builder
    final ReportBuilder rp = helper.getReportBuilder();

    try {
      Protocol protocol = getExecutionUnit().getRepository().getProtocol();
      if (protocol instanceof HarvestProtocolAgp2Agp) {
        HarvestProtocolAgp2Agp agp2agp = (HarvestProtocolAgp2Agp)protocol;
        AgpSource source = agp2agp.getSource();
        AgpDestination destination = agp2agp.getDestination();
        AgpPush agpPush = new AgpPush(source, destination) {
          @Override
          protected boolean syncItem(AgpItem sourceItem) throws Exception {
            String sourceUri = sourceItem.getProperties().getValue("id");
            try {
              boolean result = super.syncItem(sourceItem);
              if (result) {
                rp.createEntry(sourceUri, true);
              }
              LOGGER.log(Level.FINEST, "[SYNCHRONIZER] Pushed item #{0} of source URI: \"{1}\" through unit: {2}", new Object[]{rp.getHarvestedCount() + 1, sourceItem.getProperties().getValue("id"), unit});
              return result;
            } catch (Exception ex) {
              rp.createUnpublishedEntry(sourceUri, Arrays.asList(new String[]{ex.getMessage()}));
              throw ex;
            }
          }

          @Override
          protected boolean doContinue() {
            boolean doContinue = Agp2AgpExecutor.this.isActive();
            if (!doContinue) {
              unit.setCleanupFlag(false);
            }
            return doContinue;
          }
          
        };
        agpPush.synchronize();
      }

      success = true;

      if (isActive()) {
        // save last sync date
        getExecutionUnit().getRepository().setLastSyncDate(rp.getStartTime());
        HrUpdateLastSyncDate updLastSyncDate = new HrUpdateLastSyncDate(context, unit.getRepository());
        updLastSyncDate.execute();
      }
    } catch (Exception ex) {
      rp.setException(ex);
      unit.setCleanupFlag(false);
      LOGGER.log(Level.FINEST, "[SYNCHRONIZER] Failed pushing through unit: {0}. Cause: {1}", new Object[]{unit, ex.getMessage()});
      getProcessor().onIterationException(getExecutionUnit(), ex);
    } finally {
      if (!isShutdown()) {
        getProcessor().onEnd(unit, success);
        context.onExecutionPhaseCompleted();
      }
      if (result != null) {
        result.destroy();
      }
      LOGGER.log(Level.FINEST, "[SYNCHRONIZER] Completed pushing through unit: {0}. Obtained {1} records.", new Object[]{unit, count});
    }
  }
}
