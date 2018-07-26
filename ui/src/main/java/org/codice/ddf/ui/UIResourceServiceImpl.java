package org.codice.ddf.ui;

import java.net.URL;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang.StringUtils;

public class UIResourceServiceImpl implements UIResourceService {

  private static final Logger LOGGER = LoggerFactory.getLogger(UIResourceServiceImpl.class);

  private String version;
  private String groupId;
  private String artifactId;

  public UIResourceServiceImpl() {
    Bundle bundle = FrameworkUtil.getBundle(UIResourceServiceImpl.class);
    if (bundle != null) {
      version = formatVersion(bundle.getVersion());
      groupId = bundle.getLocation().split(":")[1].split("/")[0];
      artifactId = bundle.getSymbolicName();
    } else {
      LOGGER.warn("UI Resource Service was unable to retrieve its bundle.");
    }
  }

  @Override
  public URL getResource(String uiModuleName, String resourceName) {
    return null;
  }

  private String formatVersion(Version version) {
    return String.format("%s.%s.%s%s", version.getMajor(), version.getMinor(), version.getMicro(),
        StringUtils.isEmpty(version.getQualifier()) ? "" : "-" + version.getQualifier());
  }
}
