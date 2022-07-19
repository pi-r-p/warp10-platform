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

package io.warp10.script.functions;

import io.warp10.WarpConfig;
import io.warp10.WarpURLEncoder;
import io.warp10.continuum.Configuration;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;
import io.warp10.warp.sdk.Capabilities;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
/**
 * Call a subprogram
 */
public class CALL extends NamedWarpScriptFunction implements WarpScriptStackFunction {

  private static final Logger LOG = LoggerFactory.getLogger(CALL.class);

  private static int maxCapacity;

  private static long maxWait;

  private static final String MAXWAIT_CAPABILITY = "call.maxwait";

  static {
    maxCapacity = Integer.parseInt(WarpConfig.getProperty(Configuration.WARPSCRIPT_CALL_MAXCAPACITY, "1"));
    maxWait = Long.parseLong(WarpConfig.getProperty(Configuration.WARPSCRIPT_CALL_MAXWAIT, "10000"));
  }

  private static class ProcessPool {

    private final ReentrantLock mutex = new ReentrantLock(true);

    private List<Process> processes = new ArrayList<Process>();

    private Map<Process,BufferedReader> readers = new HashMap<Process,BufferedReader>();

    private AtomicInteger loaned = new AtomicInteger(0);

    private ProcessBuilder builder;

    private int capacity = 0;

    public ProcessPool(String path) {
      this.builder = new ProcessBuilder(path);

      // Make sure to terminate child processes
      Thread hook = new Thread() {
        @Override
        public void run() {
          Process proc = null;
          try {
            while (!processes.isEmpty()) {
              proc = processes.remove(0);
              LOG.info("Ending CALL subprocess " + path);
              proc.destroy();
            }
          } catch (Exception e) {
            LOG.warn("Error ending CALL subprocess" + path);
          }
        }
      };

      Runtime.getRuntime().addShutdownHook(hook);
    }

    public void provision() throws IOException {

      try {
        mutex.lockInterruptibly();

        if (capacity > 0 && processes.size() + loaned.get() >= capacity) {
          return;
        }

        //
        // Create a process, retrieving the configured capacity
        //

        Process proc = this.builder.start();
        BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        String cap = br.readLine();

        if (null == cap) {
          throw new RuntimeException("Subprogram '" + this.builder.command().toString() + "' did not return its configured capacity.");
        }

        this.capacity = Integer.parseInt(cap);

        if (this.capacity > maxCapacity || this.capacity < 0) {
          this.capacity = maxCapacity;
        }

        this.processes.add(proc);
        this.readers.put(proc, br);
      } catch (InterruptedException ie) {
        throw new IOException("Interrupted while provisioning process.", ie);
      } finally {
        if (mutex.isHeldByCurrentThread()) {
          mutex.unlock();
        }
      }
    }

    public Process get(long maxWait) throws IOException {
      Process proc = null;

      long deadline = System.currentTimeMillis() + maxWait;

      while(null == proc && !Thread.currentThread().isInterrupted()) {
        try {
          mutex.lockInterruptibly();

          if (processes.isEmpty()) {
            provision();
          }

          if (!processes.isEmpty()) {
            proc = processes.remove(0);
            if (isAlive(proc)) {
              this.loaned.addAndGet(1);
              return proc;
            } else {
              this.readers.remove(proc);
              proc = null;
            }
          }
        } catch (InterruptedException ie) {
          throw new IOException("Interrupted while retrieving process.", ie);
        } finally {
          if (mutex.isHeldByCurrentThread()) {
            mutex.unlock();
          }
        }

        if (System.currentTimeMillis() >= deadline) {
          throw new IOException("Timed out waiting for available process.");
        }

        LockSupport.parkNanos(100000L);
      }

      if (Thread.currentThread().isInterrupted()) {
        throw new IOException("Interrupted while retrieving process.");
      }

      return proc;
    }

    public void release(Process proc) {
      try {
        mutex.lockInterruptibly();
        if (isAlive(proc)) {
          processes.add(proc);
        } else {
          this.readers.remove(proc);
        }
        this.loaned.addAndGet(-1);
      } catch (InterruptedException ie) {
        throw new RuntimeException("Interrupted while releasing process.", ie);
      } finally {
        if (mutex.isHeldByCurrentThread()) {
          mutex.unlock();
        }
      }
    }

    public BufferedReader getReader(Process proc) {
      return this.readers.get(proc);
    }
  }

  /**
   * Map of subprogram name to process pool
   */
  private Map<String,ProcessPool> subprograms = new HashMap<String,ProcessPool>();

  public CALL(String name) {
    super(name);
  }

  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    Object subprogram = stack.pop();
    Object args = stack.pop();

    if (!(subprogram instanceof String) || !(args instanceof String)) {
      throw new WarpScriptException(getName() + " expects a subprogram name on top of an argument string.");
    }

    init(subprogram.toString());

    int attempts = 2;

    long maxWait = CALL.maxWait;

    if (null != Capabilities.get(stack, MAXWAIT_CAPABILITY)) {
      maxWait = Long.parseLong(Capabilities.get(stack, MAXWAIT_CAPABILITY));
    }

    while (attempts > 0) {
      Process proc = null;

      try {
        attempts--;

        proc = subprograms.get(subprogram).get(maxWait);

        if (null == proc) {
          throw new WarpScriptException(getName() + " unable to acquire subprogram.");
        }

        // 
        // Ignore previous possible unexpected output from the process, warn user in the logs
        //
        BufferedReader br = subprograms.get(subprogram).getReader(proc);
        String sbr;
        while (br.ready()) {
          sbr = br.readLine();
          LOG.warn("skipping unexpected CALL output from " + subprogram.toString() + " (" + StringUtils.substring(sbr, 0, 1000) + "...)");
        }

        //
        // Output the URLencoded string to the subprogram
        //

        proc.getOutputStream().write(WarpURLEncoder.encode(args.toString(), StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
        proc.getOutputStream().write('\n');
        proc.getOutputStream().flush();

        String ret = br.readLine();

        if (null == ret) {
          throw new WarpScriptException(getName() + " subprogram died unexpectedly.");
        }

        //
        // If the return value starts with a space then it is considered an exception whose
        // URL encoded message is the rest of the line. The space cannot otherwise occur since
        // we URL encode the return values.
        //

        // Legit uses of URLDecoder.decode

        if (ret.startsWith(" ")) {
          throw new WarpScriptException(URLDecoder.decode(ret.substring(1), StandardCharsets.UTF_8.name()));
        }

        stack.push(URLDecoder.decode(ret, StandardCharsets.UTF_8.name()));

        break;
      } catch (IOException ioe) {
        if (attempts > 0) {
          continue;
        }
        throw new WarpScriptException(ioe);
      } finally {
        if (null != proc) {
          subprograms.get(subprogram).release(proc);
        }
      }
    }

    return stack;
  }

  private synchronized void init(String subprogram) throws WarpScriptException {
    if (this.subprograms.containsKey(subprogram)) {
      return;
    }

    String dir = WarpConfig.getProperty(Configuration.WARPSCRIPT_CALL_DIRECTORY);

    if (null == dir) {
      throw new WarpScriptException(getName() + " configuration key '" + Configuration.WARPSCRIPT_CALL_DIRECTORY + "' not set, " + getName() + " disabled.");
    }

    File root = new File(dir);
    File f = new File(root, subprogram);

    //
    // Check if the file exists
    //

    if (!f.exists() || !f.canExecute()) {
      throw new WarpScriptException(getName() + " invalid subprogram '" + subprogram + "'.");
    }

    //
    // Check if it is under 'root'
    //

    if (!f.getAbsolutePath().startsWith(root.getAbsolutePath())) {
      throw new WarpScriptException(getName() + " invalid subprogram, not in the correct directory.");
    }

    this.subprograms.put(subprogram, new ProcessPool(f.getAbsolutePath()));
  }

  private static final boolean isAlive(Process proc) {
    try {
      proc.exitValue();
      return false;
    } catch(IllegalThreadStateException e) {
      return true;
    }
  }
}
