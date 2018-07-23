package org.codice.ddf.security.session;

import java.lang.ref.WeakReference;
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
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SingletonInMemorySessionDataStore extends AbstractSessionDataStore {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SingletonInMemorySessionDataStore.class);
  private boolean destroyed;

  static class SharingSessionInvalidator implements HttpSessionInvalidator {

    private final SingletonInMemorySessionDataStore sessionDataStore;

    SharingSessionInvalidator(SingletonInMemorySessionDataStore sessionDataStore) {
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

  private final Map<String, WeakReference<SessionData>> sessionDataMap = new ConcurrentHashMap<>();

  public SingletonInMemorySessionDataStore() {
    super();
    registerSessionManager();
  }

  private void registerSessionManager() {
    Bundle bundle = FrameworkUtil.getBundle(SingletonInMemorySessionDataStore.class);
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

  @Override
  public void doStore(String id, SessionData data, long lastSaveTime) throws Exception {
    WeakReference<SessionData> ref = new WeakReference<>(data);

    synchronized (this) {
      sessionDataMap.put(id, ref);
    }
  }

  @Override
  public Set<String> doGetExpired(Set<String> candidates) {
    return candidates;
  }

  @Override
  public boolean isPassivating() {
    return false;
  }

  @Override
  public boolean exists(String id) throws Exception {
    return sessionDataMap.containsKey(id);
  }

  @Override
  public SessionData load(String id) throws Exception {
    WeakReference<SessionData> sessionData = sessionDataMap.get(id);

    if (sessionData == null) throw new RuntimeException("No session data found");

    return sessionData.get();
  }

  @Override
  public boolean delete(String id) throws Exception {
    WeakReference<SessionData> ref;
    synchronized (this) {
      ref = sessionDataMap.remove(id);
    }

    return ref != null;
  }

  @Override
  public void initialize(SessionContext context) throws Exception {
    if (_context == null) {
      _context = context;
    }
  }

  @Override
  public void destroy() {
    destroyed = true;
    super.destroy();
  }

  public boolean isDestroyed() {
    return destroyed;
  }
}
