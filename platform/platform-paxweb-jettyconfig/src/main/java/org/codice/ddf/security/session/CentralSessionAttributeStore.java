package org.codice.ddf.security.session;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CentralSessionAttributeStore {

  private List<AttributeSharingSessionDataStore> dataStores = new ArrayList<>();
  private Map<String, WeakReference<Map<String, Object>>> latestSessionAttributes = new HashMap();

  protected void addSessionDataStore(AttributeSharingSessionDataStore dataStore) {
    dataStores.add(dataStore);
  }

  protected void provideNewSessionAttributes(
      AttributeSharingSessionDataStore callingDataStore,
      String id,
      Map<String, Object> sessionAttributes) {
    // Make sure these attributes are different than the latest.
    if (!sessionAttributes.equals(getLatestSessionAttributes(id))) {
      System.out.println("PUSHING ATTRIBUTES UPDATE");
      latestSessionAttributes.put(id, new WeakReference<>(sessionAttributes));
      synchronized (this) {
        dataStores.forEach(
            ds -> {
              if (ds != callingDataStore) ds.updateSessionAttributes(id, sessionAttributes);
            });
      }
    }
  }

  protected Map<String, Object> getLatestSessionAttributes(String id) {
    WeakReference<Map<String, Object>> sessionAttributes = latestSessionAttributes.get(id);
    return sessionAttributes != null ? sessionAttributes.get() : null;
  }
}
