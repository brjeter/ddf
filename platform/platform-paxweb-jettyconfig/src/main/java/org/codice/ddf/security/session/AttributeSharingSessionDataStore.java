package org.codice.ddf.security.session;

import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.servlet.http.HttpSession;
import org.codice.ddf.configuration.DictionaryMap;
import org.codice.ddf.platform.session.api.HttpSessionInvalidator;
import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.Session;
import org.eclipse.jetty.server.session.SessionData;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// put(): See if attributes are different from the central store
public class AttributeSharingSessionDataStore extends AbstractSessionDataStore {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(AttributeSharingSessionDataStore.class);

  static class SharingSessionInvalidator implements HttpSessionInvalidator {

    private final AttributeSharingSessionDataStore sessionDataStore;

    SharingSessionInvalidator(AttributeSharingSessionDataStore sessionDataStore) {
      this.sessionDataStore = sessionDataStore;
    }

    @Override
    public void invalidateSession(
        String subjectName, Function<HttpSession, String> sessionSubjectExtractor) {

      final List<Session> matchingSessions =
          getHttpSessionStream()
              .filter(s -> subjectName.equals(sessionSubjectExtractor.apply(s)))
              .filter(Session.class::isInstance)
              .map(Session.class::cast)
              .distinct()
              .collect(Collectors.toList());
      for (Session matchingSession : matchingSessions) {
        matchingSession.invalidate();
      }
    }

    private Stream<HttpSession> getHttpSessionStream() {
      return getFullSessions().stream().filter(Objects::nonNull);
    }

    private Collection<HttpSession> getFullSessions() {
      return Collections.unmodifiableCollection(null);
    }
  }

  private final Map<String, SessionData> sessionDataMap = new ConcurrentHashMap<>();
  private final CentralSessionAttributeStore centralSessionAttributeStore;

  public AttributeSharingSessionDataStore(
      CentralSessionAttributeStore centralSessionAttributeStore) {
    super();
    registerSessionManager();
    this.centralSessionAttributeStore = centralSessionAttributeStore;
  }

  private void registerSessionManager() {
    Bundle bundle = FrameworkUtil.getBundle(AttributeSharingSessionDataStore.class);
    if (bundle == null) {
      LOGGER.error("Error initializing Session Manager");
      return;
    }
    final BundleContext bundleContext = bundle.getBundleContext();
    if (bundleContext == null) {
      LOGGER.error("Error initializing Session Manager");
      return;
    }

    final SharingSessionInvalidator sm = new SharingSessionInvalidator(this);
    final Dictionary<String, Object> props = new DictionaryMap<>();
    props.put(Constants.SERVICE_PID, sm.getClass().getName());
    props.put(Constants.SERVICE_DESCRIPTION, "Sharing Session Invalidator");
    props.put(Constants.SERVICE_VENDOR, "Codice Foundation");
    props.put(Constants.SERVICE_RANKING, Integer.MIN_VALUE);

    bundleContext.registerService(HttpSessionInvalidator.class.getName(), sm, props);
  }

  /**
   * Called by the {@link CentralSessionAttributeStore} to update this SessionDataStore with newly
   * updated session attributes.
   */
  public void updateSessionAttributes(String id, Map<String, Object> sessionAttributes) {
    SessionData sessionData = sessionDataMap.get(id);
    if (sessionData != null && !sessionData.getAllAttributes().equals(sessionAttributes)) {
      sessionData.clearAllAttributes();
      sessionData.putAllAttributes(sessionAttributes);
    }
  }

  @Override
  public void doStore(String id, SessionData data, long lastSaveTime) {
    centralSessionAttributeStore.provideNewSessionAttributes(this, id, data.getAllAttributes());

    synchronized (this) {
      sessionDataMap.put(id, data);
    }
  }

  @Override
  public SessionData newSessionData(
      String id, long created, long accessed, long lastAccessed, long maxInactiveMs) {
    SessionData sessionData =
        new SessionData(
            id,
            _context.getCanonicalContextPath(),
            _context.getVhost(),
            created,
            accessed,
            lastAccessed,
            maxInactiveMs);

    Map<String, Object> sessionAttributes =
        centralSessionAttributeStore.getLatestSessionAttributes(id);
    if (sessionAttributes != null) sessionData.putAllAttributes(sessionAttributes);

    return sessionData;
  }

  @Override
  public Set<String> doGetExpired(Set<String> candidates) {
    final long now = System.currentTimeMillis();

    return candidates
        .stream()
        .filter(
            c -> {
              SessionData sessionData = sessionDataMap.get(c);
              return sessionData != null && sessionData.getExpiry() > now;
            })
        .collect(Collectors.toSet());
  }

  @Override
  public boolean isPassivating() {
    return true;
  }

  @Override
  public boolean exists(String id) {
    return sessionDataMap.containsKey(id);
  }

  @Override
  public SessionData load(String id) {
    SessionData sessionData = sessionDataMap.get(id);

    return sessionData;
  }

  @Override
  public boolean delete(String id) {
    SessionData sessionData;
    synchronized (this) {
      sessionData = sessionDataMap.remove(id);
    }

    return sessionData != null;
  }
}
