/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.admin.application.service.command;

import static org.boon.Boon.fromJson;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.inject.Inject;

import org.apache.commons.io.IOUtils;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.codice.ddf.admin.application.service.ApplicationService;
import org.codice.ddf.admin.application.service.ApplicationServiceException;
import org.codice.ddf.security.common.Security;
import org.codice.ddf.ui.admin.api.GuestClaimsHandlerExt;
import org.codice.ddf.ui.admin.api.impl.SystemPropertiesAdmin;
import org.codice.ddf.ui.admin.api.util.PropertiesFileReader;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ddf.security.Subject;
import ddf.security.SubjectUtils;

/**
 * Utilizes the OSGi Command Shell in Karaf and lists all available
 * applications.
 */
@Command(scope = "admin", name = "configure", description = "Configures the system given a configuration file")
@Service
public class ConfigureCommand extends AbstractApplicationCommand {
    @Argument(index = 0, name = "filePath", description = "Path to config file", required = true, multiValued = false)
    String filePath;

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigureCommand.class);

    @Inject
    protected ConfigurationAdmin configAdmin;

    private org.codice.ddf.ui.admin.api.ConfigurationAdmin ddfConfigAdmin;

    private SystemPropertiesAdmin systemPropsAdmin;

    public ConfigureCommand() {
        String ddfHome = System.getProperty("ddf.home");
        GuestClaimsHandlerExt guestClaimsHandler =
                new GuestClaimsHandlerExt(new PropertiesFileReader(),
                        Arrays.asList(
                                "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/nameidentifier",
                                "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/role"),
                        ddfHome + "/etc/ws-security/attributeMap.properties",
                        ddfHome + "/etc/ws-security/profiles.json");
        guestClaimsHandler.init();

        ddfConfigAdmin =
                new org.codice.ddf.ui.admin.api.ConfigurationAdmin((ConfigurationAdmin) getService(
                        ConfigurationAdmin.class));
        ddfConfigAdmin.setGuestClaimsHandlerExt(guestClaimsHandler);

        systemPropsAdmin = new SystemPropertiesAdmin(guestClaimsHandler);
    }

    @Override
    protected void doExecute(ApplicationService applicationService)
            throws ApplicationServiceException {
        String json;
        try (InputStream inputStream = new FileInputStream(filePath)) {
            json = IOUtils.toString(inputStream);
            //            System.out.println("Config contents: " + json);

        } catch (IOException e) {
            LOGGER.debug("Could not find config file: ", e);
            return;
        }

        Map<String, Object> mapOfJson = (Map<String, Object>) fromJson(json);

        System.out.println("Updating Guest Profile");
        updateGuestClaimsProfile((String) mapOfJson.get("guest-profile"));

        System.out.println("Starting apps");
        startApps(applicationService, (List<String>) mapOfJson.get("startup-apps"));

        System.out.println("Updating system properties");
        writeSystemProperties((Map<String, String>) mapOfJson.get("system-properties"));

        FeaturesService featuresService = (FeaturesService) getService(FeaturesService.class);

        if (featuresService == null) {
            System.out.println("Features Service was null");
        } else {
            System.out.println("Starting features");
            uninstallInstallerModule(featuresService);
            startFeatures(featuresService, (List<String>) mapOfJson.get("startup-features"));
        }

        System.out.println("Configuration complete.");

        return;
    }

    private void startApps(ApplicationService applicationService, List<String> startupApps)
            throws ApplicationServiceException {
        for (String application : startupApps) {
            LOGGER.info("Starting app: " + application);
            executeAsSystem(() -> {
                applicationService.startApplication(application);
                return true;
            });
        }
    }

    private void updateGuestClaimsProfile(String profileName) {
        if (profileName != null) {
            Map<String, Object> profileMap = new HashMap<String, Object>();
            profileMap.put("profile", profileName);
            try {
                ddfConfigAdmin.updateGuestClaimsProfile("ddf.security.sts.guestclaims", profileMap);
            } catch (IOException e) {
                LOGGER.debug("Failed to update guest claims profile.", e);
            }
        }
    }

    private void writeSystemProperties(Map<String, String> systemProps) {
        systemPropsAdmin.writeSystemProperties(systemProps);
    }

    private void uninstallInstallerModule(FeaturesService featuresService) {
        try {
            if (!featuresService.isInstalled(featuresService.getFeature("admin-post-install-modules"))) {
                featuresService.installFeature("admin-post-install-modules",
                        EnumSet.of(FeaturesService.Option.NoAutoRefreshBundles));
            }
            if (featuresService.isInstalled(featuresService.getFeature("admin-modules-installer"))) {
                featuresService.uninstallFeature("admin-modules-installer");
            }
        } catch (Exception e) {
            LOGGER.debug("Error while trying to run the post-install start and stop operations.",
                    e);
        }
    }

    private void startFeatures(FeaturesService featuresService, List<String> startupFeatures) {
        try {
            for (String feature : startupFeatures) {
                LOGGER.info("Installing feature: " + feature);
                executeAsSystem(() -> {
                    featuresService.installFeature(feature);
                    return true;
                });
            }
        } catch (Exception e) {
            LOGGER.debug("Error while trying to install features.", e);
        }
    }

    private Object getService(Class clazz) {
        BundleContext context;
        Bundle bundle = FrameworkUtil.getBundle(ConfigureCommand.class);
        if (bundle != null) {
            context = bundle.getBundleContext();
            ServiceReference<FeaturesService> serviceRef = context.getServiceReference(clazz);
            return context.getService(serviceRef);
        }

        return null;
    }

    private <T> T executeAsSystem(Callable<T> func) {
        Subject systemSubject = getSystemSubject();
        LOGGER.info("System Subject retrieved: " + SubjectUtils.getName(systemSubject));
        if (systemSubject == null) {
            throw new RuntimeException("Could not get system user to auto install applications.");
        }
        return systemSubject.execute(func);
    }

    Subject getSystemSubject() {
        return Security.runAsAdmin(() -> Security.getInstance()
                .getSystemSubject());
    }
}
