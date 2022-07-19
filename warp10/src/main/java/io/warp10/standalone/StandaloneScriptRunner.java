//
//   Copyright 2018-2022  SenX S.A.S.
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.standalone;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.geoxp.oss.CryptoHelper;

import com.google.common.primitives.Longs;

import io.warp10.WarpConfig;
import io.warp10.continuum.BootstrapManager;
import io.warp10.continuum.Configuration;
import io.warp10.continuum.TimeSource;
import io.warp10.continuum.sensision.SensisionConstants;
import io.warp10.continuum.store.Constants;
import io.warp10.continuum.store.DirectoryClient;
import io.warp10.continuum.store.StoreClient;
import io.warp10.crypto.KeyStore;
import io.warp10.crypto.OrderPreservingBase64;
import io.warp10.script.MemoryWarpScriptStack;
import io.warp10.script.ScriptRunner;
import io.warp10.script.WarpScriptLib;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStack.StackContext;
import io.warp10.script.WarpScriptStackRegistry;
import io.warp10.sensision.Sensision;

public class StandaloneScriptRunner extends ScriptRunner {

  private final StoreClient storeClient;
  private final DirectoryClient directoryClient;
  private final Properties props;
  private final BootstrapManager bootstrapManager;

  private final byte[] runnerPSK;

  private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)\\}");

  /**
   * runContexts < script path , hashmap > can store context objects for next runner iteration.
   * Currently stores only runner.execution.counter, but can be extended later.
   */
  final Map<String, HashMap> runContexts = new ConcurrentHashMap<String, HashMap>();

  public StandaloneScriptRunner(Properties properties, KeyStore keystore, StoreClient storeClient, DirectoryClient directoryClient, Properties props) throws IOException {
    super(keystore, props);

    this.props = props;
    this.directoryClient = directoryClient;
    this.storeClient = storeClient;

    //
    // Check if we have a 'bootstrap' property
    //

    if (properties.containsKey(Configuration.CONFIG_WARPSCRIPT_RUNNER_BOOTSTRAP_PATH)) {
      final String path = properties.getProperty(Configuration.CONFIG_WARPSCRIPT_RUNNER_BOOTSTRAP_PATH);

      long period = properties.containsKey(Configuration.CONFIG_WARPSCRIPT_RUNNER_BOOTSTRAP_PERIOD) ? Long.parseLong(properties.getProperty(Configuration.CONFIG_WARPSCRIPT_RUNNER_BOOTSTRAP_PERIOD)) : 0L;
      this.bootstrapManager = new BootstrapManager(path, period);
    } else {
      this.bootstrapManager = new BootstrapManager();
    }

    this.runnerPSK = keystore.getKey(KeyStore.AES_RUNNER_PSK);
  }

  @Override
  protected void schedule(final Map<String, Long> nextrun, final String script, final long periodicity) {

    try {

      final long scheduledat = System.currentTimeMillis();

      this.executor.submit(new Runnable() {
        @Override
        public void run() {
          String name = currentThread().getName();
          currentThread().setName(script);

          long nowns = System.nanoTime();

          File f = new File(script);

          Map<String, String> labels = new HashMap<String, String>();
          //labels.put(SensisionConstants.SENSISION_LABEL_PATH, Long.toString(periodicity) + "/" + f.getName());
          String path = f.getAbsolutePath().substring(getRoot().length() + 1);
          labels.put(SensisionConstants.SENSISION_LABEL_PATH, path);

          long ttl = Math.max(scanperiod * 2, periodicity * 2);

          Sensision.update(SensisionConstants.SENSISION_CLASS_WARPSCRIPT_RUN_COUNT, labels, ttl, 1);

          long nano = System.nanoTime();

          HashMap runContext = runContexts.getOrDefault(script, new HashMap());
          Long execCount = (Long) runContext.getOrDefault(Constants.RUNNER_CONTEXT_EXEC_COUNT, 0L);

          WarpScriptStack stack = new MemoryWarpScriptStack(storeClient, directoryClient, props);
          stack.setAttribute(WarpScriptStack.ATTRIBUTE_NAME, "[StandaloneScriptRunner " + script + "]");

          ByteArrayOutputStream baos = new ByteArrayOutputStream();

          long periodicityForNextRun = periodicity;  // can be overriden by RUNNERIN
          long runnerAtForNextRun = Long.MAX_VALUE;  // can be overriden by RUNNERAT

          try {
            WarpConfig.setThreadProperty(WarpConfig.THREAD_PROPERTY_SESSION, UUID.randomUUID().toString());
            Sensision.update(SensisionConstants.SENSISION_CLASS_WARPSCRIPT_RUN_CURRENT, Sensision.EMPTY_LABELS, 1);

            InputStream in = new FileInputStream(f);

            byte[] buf = new byte[1024];

            while (true) {
              int len = in.read(buf);

              if (len < 0) {
                break;
              }

              baos.write(buf, 0, len);
            }

            // Add a 'CLEAR' at the end of the script so we don't return anything
            baos.write(CLEAR);

            in.close();

            //
            // Replace the context with the bootstrap one
            //

            StackContext context = bootstrapManager.getBootstrapContext();

            if (null != context) {
              stack.push(context);
              stack.restore();
            }

            //
            // Execute the bootstrap code
            //

            stack.exec(WarpScriptLib.BOOTSTRAP);

            stack.store(Constants.RUNNER_PERIODICITY, periodicity);
            stack.store(Constants.RUNNER_PATH, path);
            stack.store(Constants.RUNNER_SCHEDULEDAT, scheduledat);
            stack.store(Constants.RUNNER_CONTEXT_EXEC_COUNT, execCount);

            //
            // Generate a nonce by wrapping the current time jointly with random 64bits
            //

            if (null != runnerPSK) {
              byte[] now = Longs.toByteArray(TimeSource.getNanoTime());

              byte[] nonce = CryptoHelper.wrapBlob(runnerPSK, now);

              stack.store(Constants.RUNNER_NONCE, new String(OrderPreservingBase64.encode(nonce), StandardCharsets.US_ASCII));
            }

            String mc2 = new String(baos.toByteArray(), StandardCharsets.UTF_8);

            // Replace ${name} and ${name:default} constructs

            Matcher m = VAR.matcher(mc2);

            // Strip the period out of the path and add a leading '/'
            String rawpath = "/" + path.replaceFirst("/" + periodicity + "/", "/");
            // Remove the file extension
            rawpath = rawpath.substring(0, rawpath.length() - 4);

            StringBuffer mc2WithReplacement = new StringBuffer();

            while (m.find()) {
              String var = m.group(1);
              String def = m.group(0);

              int colonIndex = var.indexOf(':');
              if (colonIndex >= 0) {
                def = var.substring(colonIndex + 1);
                var = var.substring(0, colonIndex);
              }

              // Check in the configuration if we can find a matching key, i.e.
              // name@/path/to/script (with the period omitted) or any shorter prefix
              // of the path, i.e. name@/path/to or name@/path
              String suffix = rawpath;

              String value = null;

              while (suffix.length() > 1) {
                value = WarpConfig.getProperty(var + "@" + suffix);
                if (null != value) {
                  break;
                }
                suffix = suffix.substring(0, suffix.lastIndexOf('/'));
              }

              if (null == value) {
                value = def;
              }

              m.appendReplacement(mc2WithReplacement, Matcher.quoteReplacement(value));
            }

            m.appendTail(mc2WithReplacement);

            stack.execMulti(mc2WithReplacement.toString());

            // Did the user asked to reschedule script to another period with RUNNERIN ?
            if (stack.getAttribute(WarpScriptStack.ATTRIBUTE_RUNNER_RESCHEDULE_PERIOD) instanceof Long) {
              periodicityForNextRun = (Long) stack.getAttribute(WarpScriptStack.ATTRIBUTE_RUNNER_RESCHEDULE_PERIOD);
            }
            // Did the user asked to reschedule script to another absolute time with RUNNERAT ? (absolute milliseconds)
            if (stack.getAttribute(WarpScriptStack.ATTRIBUTE_RUNNER_RESCHEDULE_TIMESTAMP) instanceof Long) {
              runnerAtForNextRun = (Long) stack.getAttribute(WarpScriptStack.ATTRIBUTE_RUNNER_RESCHEDULE_TIMESTAMP);
            }
          } catch (Exception e) {
            Sensision.update(SensisionConstants.SENSISION_CLASS_WARPSCRIPT_RUN_FAILURES, labels, 1);
          } finally {
            WarpConfig.clearThreadProperties();
            WarpScriptStackRegistry.unregister(stack);
            currentThread().setName(name);
            // Manage possible overflow (MAXLONG RUNNERIN in all possible platform time unit)
            long runnerInTime = Long.MAX_VALUE;
            if (periodicityForNextRun < (Long.MAX_VALUE / 1000000L) && (nowns + periodicityForNextRun * 1000000L) > nowns) {
              runnerInTime = nowns + periodicityForNextRun * 1000000L;
            }
            // Convert absolute time in millisecond to jvm nano time
            long runnerAtTime = TimeSource.currentTimeMillisToNanoTime(runnerAtForNextRun);
            // Next script is scheduled at min(RUNNERAT, RUNNERIN)
            // if none of these functions are used, it is scheduled at period defined by script path.
            nextrun.put(script, Math.min(runnerInTime, runnerAtTime));
            nano = System.nanoTime() - nano;
            Sensision.update(SensisionConstants.SENSISION_CLASS_WARPSCRIPT_RUN_TIME_US, labels, ttl, nano / 1000L);
            Sensision.update(SensisionConstants.SENSISION_CLASS_WARPSCRIPT_RUN_ELAPSED, labels, ttl, nano);
            Sensision.update(SensisionConstants.SENSISION_CLASS_WARPSCRIPT_RUN_OPS, labels, ttl, (long) stack.getAttribute(WarpScriptStack.ATTRIBUTE_OPS));
            Sensision.update(SensisionConstants.SENSISION_CLASS_WARPSCRIPT_RUN_FETCHED, labels, ttl, ((AtomicLong) stack.getAttribute(WarpScriptStack.ATTRIBUTE_FETCH_COUNT)).get());
            Sensision.update(SensisionConstants.SENSISION_CLASS_WARPSCRIPT_RUN_CURRENT, Sensision.EMPTY_LABELS, -1);
            runContext.put(Constants.RUNNER_CONTEXT_EXEC_COUNT, execCount + 1);
            runContexts.put(script, runContext);
          }
        }
      });
    } catch (RejectedExecutionException ree) {
      // Reschedule script immediately
      nextrun.put(script, System.nanoTime());
    }
  }

  /**
   * When a script is removed from disk, call this function to remove the attached context
   *
   * @param scriptName
   */
  @Override
  protected void removeRunnerContext(String scriptName) {
    runContexts.remove(scriptName);
  }
}
