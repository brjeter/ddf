package org.codice.ddf.security.session;

import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.eclipse.jetty.server.session.SessionHandler;

public class AttributeSharingSessionDataStoreFactory implements SessionDataStoreFactory {
  private CentralSessionAttributeStore centralSessionAttributeStore;

  public AttributeSharingSessionDataStoreFactory() {
    centralSessionAttributeStore = new CentralSessionAttributeStore();
  }

  @Override
  public SessionDataStore getSessionDataStore(SessionHandler handler) {
    AttributeSharingSessionDataStore dataStore =
        new AttributeSharingSessionDataStore(centralSessionAttributeStore);
    centralSessionAttributeStore.addSessionDataStore(dataStore);
    return dataStore;
  }
}
