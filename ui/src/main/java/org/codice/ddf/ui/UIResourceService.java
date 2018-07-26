package org.codice.ddf.ui;

import java.net.URL;

public interface UIResourceService {
  public URL getResource(String uiModuleName, String resourceName);
}
