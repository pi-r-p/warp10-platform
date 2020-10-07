package io.warp10.standalone.datalog;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import io.warp10.continuum.store.thrift.data.DatalogRecord;

public class DatalogWorkers {
  
  private static final Map<String,TCPDatalogConsumer> consumers = new ConcurrentHashMap<String,TCPDatalogConsumer>();
  
  static class DatalogJob {
    final TCPDatalogConsumer consumer;
    final String ref;
    final DatalogRecord record;
    
    public DatalogJob(TCPDatalogConsumer consumer, String ref, DatalogRecord record) {
      this.consumer = consumer;
      this.ref = ref;
      this.record = record;
    }    
  }
  
  private static final LinkedBlockingQueue<DatalogJob> queues[];
  private static final DatalogWorker[] workers;
  
  private static final int NUM_WORKERS = 8;
  
  static {
    queues = new LinkedBlockingQueue[NUM_WORKERS];
    workers = new DatalogWorker[NUM_WORKERS];
    
    for (int i = 0; i < NUM_WORKERS; i++) {
      queues[i] = new LinkedBlockingQueue<DatalogJob>();
      workers[i] = new DatalogWorker(queues[i]);      
    }
  }
  
  public static void offer(TCPDatalogConsumer consumer, String ref, DatalogRecord record) throws IOException {
    //
    // Compute partitioning key
    //

    long classid = record.getMetadata().getClassId();
    long labelsid = record.getMetadata().getLabelsId();
    
    int partkey = (int) (((classid << 16) & 0xFFFFL) | ((labelsid >>> 48) & 0xFFFFL));
    
    DatalogJob job = new DatalogJob(consumer, ref, record);
    
    if (!queues[partkey % NUM_WORKERS].offer(job)) {
      throw new IOException("Unable to offer record '" + ref + "'.");
    }
  }  
}
