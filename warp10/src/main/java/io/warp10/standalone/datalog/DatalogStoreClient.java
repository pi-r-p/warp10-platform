package io.warp10.standalone.datalog;

import java.io.IOException;

import io.warp10.continuum.gts.GTSEncoder;
import io.warp10.continuum.store.GTSDecoderIterator;
import io.warp10.continuum.store.StoreClient;
import io.warp10.continuum.store.thrift.data.FetchRequest;
import io.warp10.continuum.store.thrift.data.Metadata;
import io.warp10.quasar.token.thrift.data.WriteToken;
import io.warp10.standalone.StandalonePlasmaHandlerInterface;

public class DatalogStoreClient implements StoreClient {

  private final DatalogManager manager;
  private final StoreClient store;
  
  public DatalogStoreClient(DatalogManager manager, StoreClient store) {
    this.manager = manager;
    this.store = store;
  }
  
  @Override
  public GTSDecoderIterator fetch(FetchRequest req) throws IOException {
    return store.fetch(req);
  }

  @Override
  public void store(GTSEncoder encoder) throws IOException {
    // CAUTION, StoreClient#store assumes that class and labels ids
    // HAVE BEEN computed
    store.store(encoder);
    manager.store(encoder);
  }

  @Override
  public long delete(WriteToken token, Metadata metadata, long start, long end) throws IOException {
    // We store the delete message BEFORE the actual delete operation, this may cause a message
    // to be logged for a delete operation that ultimately fails, but it is in the general case better
    // as deletes can take a long time and therefore the order of data coming after the delete for the
    // same series could be swapped if the delete op is recorded after the op instead of before.
    manager.delete(token, metadata, start, end);
    return store.delete(token, metadata, start, end);
  }

  @Override
  public void addPlasmaHandler(StandalonePlasmaHandlerInterface plasmaHandler) {
    store.addPlasmaHandler(plasmaHandler);
  }
}
