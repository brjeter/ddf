/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.pax.web.jetty;

import ddf.security.SecurityConstants;
import ddf.security.Subject;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.codice.ddf.platform.filter.SecurityFilter;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.UserIdentity;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.slf4j.LoggerFactory;

public class JettyAuthenticator extends LoginAuthenticator {

  private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(JettyAuthenticator.class);
  private CopyOnWriteArraySet<String> keysOfInitializedSecurityFilters;

  public JettyAuthenticator() {
    super();
    keysOfInitializedSecurityFilters = new CopyOnWriteArraySet<>();
    _loginService = new DummyLoginService();
    _identityService = _loginService.getIdentityService();
  }

  @Override
  public void setConfiguration(AuthConfiguration configuration) {
    keysOfInitializedSecurityFilters.clear();
    if (configuration instanceof ConstraintSecurityHandler) {
      ((ConstraintSecurityHandler) configuration).setLoginService(_loginService);
      ((ConstraintSecurityHandler) configuration).setIdentityService(_identityService);
    }
  }

  @Override
  public String getAuthMethod() {
    return "DDF";
  }

  @Override
  public Authentication validateRequest(
      ServletRequest servletRequest, ServletResponse servletResponse, boolean mandatory)
      throws ServerAuthException {

    TreeSet<ServiceReference<SecurityFilter>> sortedSecurityFilterServiceReferences = null;
    final BundleContext bundleContext = getContext();

    if (bundleContext == null) {
      throw new ServerAuthException(
          "Unable to get BundleContext. No servlet SecurityFilters can be applied. Blocking the request processing.");
    }

    try {
      sortedSecurityFilterServiceReferences =
          new TreeSet<>(bundleContext.getServiceReferences(SecurityFilter.class, null));
    } catch (InvalidSyntaxException ise) {
      LOGGER.debug("Should never get this exception as there is no filter being passed.");
    }

    if (!CollectionUtils.isEmpty(sortedSecurityFilterServiceReferences)) {
      LOGGER.debug(
          "Found {} filter(s), now filtering...", sortedSecurityFilterServiceReferences.size());
      final ProxyFilterChain chain = new ProxyFilterChain();

      // Insert the SecurityFilters into the chain one at a time (from lowest service ranking
      // to highest service ranking). The SecurityFilter with the highest service-ranking will
      // end up at index 0 in the FilterChain, which means that the SecurityFilters will be
      // run in order of highest to lowest service ranking.
      for (ServiceReference<SecurityFilter> securityFilterServiceReference :
          sortedSecurityFilterServiceReferences) {
        final SecurityFilter securityFilter =
            bundleContext.getService(securityFilterServiceReference);

        if (!hasBeenInitialized(securityFilterServiceReference, bundleContext)) {
          try {
            initializeSecurityFilter(
                bundleContext,
                securityFilterServiceReference,
                securityFilter,
                resolveRequest(servletRequest));
          } catch (ServletException e) {
            throw new ServerAuthException("Unable to initialize security filter");
          }
        }
        chain.addSecurityFilter(securityFilter);
      }

      try {
        chain.doFilter(servletRequest, servletResponse);
      } catch (IOException | ServletException e) {
        throw new ServerAuthException(
            "Unable to process security filter. Blocking the request processing.");
      }
      if (servletResponse instanceof HttpServletResponse
          && ((HttpServletResponse) servletResponse).getStatus() >= 300) {
        return new Authentication.Challenge() {};
      }
    } else {
      LOGGER.debug("Did not find any SecurityFilters. Acting as a pass-through filter...");
    }

    Subject subject = (Subject) servletRequest.getAttribute(SecurityConstants.SECURITY_SUBJECT);
    UserIdentity userIdentity = new JettyUserIdentity(getSecuritySubject(subject));
    return new JettyAuthenticatedUser(userIdentity);
  }

  @Nullable
  BundleContext getContext() {
    final Bundle cxfBundle = FrameworkUtil.getBundle(JettyAuthenticator.class);
    if (cxfBundle != null) {
      return cxfBundle.getBundleContext();
    }
    return null;
  }

  @Nullable
  protected Request resolveRequest(ServletRequest req) {
    return (req instanceof Request) ? (Request) req : null;
  }

  @Nullable
  private javax.security.auth.Subject getSecuritySubject(@Nullable Subject subject) {
    if (subject == null) {
      return null;
    }
    HashSet emptySet = new HashSet();
    HashSet subjectPrincipal = new HashSet();
    subjectPrincipal.add(subject);
    return new javax.security.auth.Subject(true, subjectPrincipal, emptySet, emptySet);
  }

  private boolean hasBeenInitialized(
      final ServiceReference<SecurityFilter> securityFilterServiceReference,
      final BundleContext bundleContext) {
    return keysOfInitializedSecurityFilters.contains(
        getFilterKey(securityFilterServiceReference, bundleContext));
  }

  private void initializeSecurityFilter(
      BundleContext bundleContext,
      ServiceReference<SecurityFilter> securityFilterServiceReference,
      SecurityFilter securityFilter,
      @Nullable Request request)
      throws ServletException {
    final ServletContext servletContext;
    final String filterName = getFilterName(securityFilterServiceReference, bundleContext);

    if (request == null || request.getSession() == null) {
      LOGGER.warn(
          "Request does not have a servlet context. Initializing SecurityFilter {} with null ServletContext",
          filterName);
      servletContext = null;
    } else {
      servletContext = request.getSession().getServletContext();
    }

    securityFilter.init(
        new SecurityFilterInitFilterConfig(
            securityFilterServiceReference, servletContext, bundleContext));
    keysOfInitializedSecurityFilters.add(
        getFilterKey(securityFilterServiceReference, bundleContext));
    LOGGER.debug("Initialized SecurityFilter {}", filterName);
  }

  public void removeSecurityFilter(
      final ServiceReference<SecurityFilter> securityFilterServiceReference) {
    if (securityFilterServiceReference != null) {
      final BundleContext bundleContext = getContext();
      if (bundleContext != null) {
        // unmark the SecurityFilter as initialized so that it can be re-initialized if the
        // SecurityFilter is registered again
        keysOfInitializedSecurityFilters.remove(
            getFilterKey(securityFilterServiceReference, bundleContext));
        bundleContext.getService(securityFilterServiceReference).destroy();
      } else {
        LOGGER.warn(
            "Unable to remove SecurityFilter. Try restarting the system or turning up logging to monitor current SecurityFilters.");
      }
    }
  }

  @Override
  public boolean secureResponse(
      ServletRequest req, ServletResponse res, boolean mandatory, Authentication.User validatedUser)
      throws ServerAuthException {
    return true;
  }

  @Nonnull
  private static String getFilterKey(
      final ServiceReference<SecurityFilter> securityFilterServiceReference,
      final BundleContext bundleContext) {
    return getFilterName(securityFilterServiceReference, bundleContext);
  }

  /**
   * This logic to get the filter name from a {@link ServiceReference<Filter>} is copied from {@link
   * org.ops4j.pax.web.extender.whiteboard.internal.tracker.ServletTracker#createWebElement(ServiceReference,
   * javax.servlet.Servlet)}. See the pax-web Whiteboard documentation and {@link
   * org.osgi.service.http.whiteboard.HttpWhiteboardConstants#HTTP_WHITEBOARD_FILTER_NAME} for how
   * to configure {@link Filter} services with a filter name.
   */
  @Nonnull
  private static String getFilterName(
      ServiceReference<SecurityFilter> securityFilterServiceReference,
      BundleContext bundleContext) {
    final String HTTP_WHITEBOARD_FILTER_NAME = "osgi.http.whiteboard.filter.name";
    final String filterNameFromTheServiceProperty =
        getStringProperty(securityFilterServiceReference, HTTP_WHITEBOARD_FILTER_NAME);
    // If this service property is not specified, the fully qualified name of the service object's
    // class is used as the servlet filter name.
    if (StringUtils.isBlank(filterNameFromTheServiceProperty)) {
      return bundleContext.getService(securityFilterServiceReference).getClass().getCanonicalName();
    } else {
      return filterNameFromTheServiceProperty;
    }
  }

  private static String getStringProperty(ServiceReference<?> serviceReference, String key) {
    Object value = serviceReference.getProperty(key);
    if (value != null && !(value instanceof String)) {
      LOGGER.warn("Service property [key={}] value must be a String", key);
      return null;
    } else {
      return (String) value;
    }
  }

  /**
   * This inner class is used to instantiate a {@link FilterConfig} from a {@link SecurityFilter}'s
   * service properties containing init params and the same {@link ServletContext} as the {@link
   * JettyAuthenticator}. The {@link FilterConfig} is used to initialize the {@link SecurityFilter}.
   *
   * <p>The logic of this inner class is copied from the {@link
   * org.ops4j.pax.web.extender.whiteboard} feature.
   */
  private static class SecurityFilterInitFilterConfig implements FilterConfig {

    private final ServiceReference<SecurityFilter> securityFilterServiceReference;

    private final ServletContext servletContext;

    private final Map<String, String> initParams;

    private final BundleContext bundleContext;

    SecurityFilterInitFilterConfig(
        ServiceReference<SecurityFilter> securityFilterServiceReference,
        ServletContext servletContext,
        BundleContext bundleContext) {
      this.securityFilterServiceReference = securityFilterServiceReference;
      this.servletContext = servletContext;
      this.bundleContext = bundleContext;
      initParams = createInitParams();
    }

    @Override
    public String getFilterName() {
      return JettyAuthenticator.getFilterName(securityFilterServiceReference, bundleContext);
    }

    @Override
    public ServletContext getServletContext() {
      return servletContext;
    }

    @Override
    public String getInitParameter(String name) {
      return initParams.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
      return Collections.enumeration(initParams.keySet());
    }

    /**
     * This code to create the init params from a {@link ServiceReference< Filter >} is copied from
     * {@link
     * org.ops4j.pax.web.extender.whiteboard.internal.tracker.ServletTracker#createWebElement(ServiceReference,
     * javax.servlet.Servlet)}. See the pax-web Whiteboard documentation and {@link
     * org.ops4j.pax.web.extender.whiteboard.ExtenderConstants#PROPERTY_INIT_PREFIX} for how to
     * configure {@link Filter} services with init params.
     */
    private Map<String, String> createInitParams() {
      String[] initParamKeys = securityFilterServiceReference.getPropertyKeys();
      final String PROPERTY_INIT_PREFIX = "init-prefix";
      String initPrefixProp =
          getStringProperty(securityFilterServiceReference, PROPERTY_INIT_PREFIX);
      if (initPrefixProp == null) {
        final String DEFAULT_INIT_PREFIX_PROP = "init.";
        initPrefixProp = DEFAULT_INIT_PREFIX_PROP;
      }

      // make all the service parameters available as initParams
      Map<String, String> initParameters = new HashMap<>();
      for (String key : initParamKeys) {
        String value =
            securityFilterServiceReference.getProperty(key) == null
                ? ""
                : securityFilterServiceReference.getProperty(key).toString();

        final String HTTP_WHITEBOARD_SERVLET_INIT_PARAM_PREFIX = "servlet.init.";
        if (key.startsWith(initPrefixProp)) {
          initParameters.put(key.replaceFirst(initPrefixProp, ""), value);
        } else if (key.startsWith(HTTP_WHITEBOARD_SERVLET_INIT_PARAM_PREFIX)) {
          initParameters.put(
              key.replaceFirst(HTTP_WHITEBOARD_SERVLET_INIT_PARAM_PREFIX, ""), value);
        }
      }

      return initParameters;
    }
  }

  private class DummyLoginService implements org.eclipse.jetty.security.LoginService {

    final JettyIdentityService jettyIdentityService = new JettyIdentityService();

    @Override
    public String getName() {
      return null;
    }

    @Override
    public UserIdentity login(String username, Object credentials, ServletRequest request) {
      return null;
    }

    @Override
    public boolean validate(UserIdentity user) {
      return false;
    }

    @Override
    public IdentityService getIdentityService() {
      return jettyIdentityService;
    }

    @Override
    public void setIdentityService(IdentityService service) {}

    @Override
    public void logout(UserIdentity user) {}
  }
}
