package org.codice.ddf.security.session;

import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_OK;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import com.jayway.restassured.RestAssured;
import com.jayway.restassured.filter.session.SessionFilter;
import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class JettySessionManagementTest {

  private static Server server;
  private static int port;
  private static HttpClient client;
  private static CookieStore httpCookieStore;

  @BeforeClass
  public static void setupClass() throws Exception {
    server = new Server();
    HandlerList handlers = new HandlerList();
    server.setHandler(handlers);

    // Configure server according to the jetty.xml file
    XmlConfiguration configuration = new XmlConfiguration(
        JettySessionManagementTest.class.getResourceAsStream("/jetty.xml"));
    configuration.configure(server);

    // Have server bind to first available port
    ServerConnector connector = new ServerConnector(server);
    connector.setPort(0);
    server.addConnector(connector);

    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.addServlet(new ServletHolder(new TestServlet()), "/");
    handlers.addHandler(context);

    client = HttpClientBuilder.create().build();

    server.start();

    port = connector.getLocalPort();

  }

  @AfterClass
  public static void stopJetty() {
    try {
      server.stop();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Test
  public void sessionCanBeObtained() throws Exception {
    String sessionId = RestAssured.given()
        .get(String.format("http://localhost:%s/newSession", port))
        .then()
        .statusCode(is(SC_OK))
        .extract().sessionId();

    assertThat(sessionId, is(not(nullValue())));
  }

  @Test
  public void sessionShouldBeReusable() {
    SessionFilter sessionFilter = new SessionFilter();
    String sessionId = RestAssured.given()
        .filter(sessionFilter)
        .get(String.format("http://localhost:%s/newSession", port))
        .then()
        .statusCode(is(SC_OK))
        .extract().sessionId();

    assertThat(sessionId, is(not(nullValue())));

    RestAssured.given()
        .filter(sessionFilter)
        .get(String.format("http://localhost:%s/existingSession", port))
        .then()
        .statusCode(is(SC_OK));
  }

  @Test
  public void sessionShouldNotBeUsableWhenInvalidated() {
    SessionFilter sessionFilter = new SessionFilter();
    String sessionId = RestAssured.given()
        .filter(sessionFilter)
        .get(String.format("http://localhost:%s/newSession", port))
        .then()
        .statusCode(is(SC_OK))
        .extract().sessionId();

    assertThat(sessionId, is(not(nullValue())));

    RestAssured.given()
        .filter(sessionFilter)
        .get(String.format("http://localhost:%s/existingSession", port))
        .then()
        .statusCode(is(SC_OK));

    RestAssured.given()
        .filter(sessionFilter)
        .get(String.format("http://localhost:%s/invalidateSession", port))
        .then()
        .statusCode(is(SC_OK));

    RestAssured.given()
        .filter(sessionFilter)
        .get(String.format("http://localhost:%s/existingSession", port))
        .then()
        .statusCode(is(SC_BAD_REQUEST));
  }

  // SamlAssertionHandler 146: addSession before AuthnRequest
  // ACS 343: removeSession and invalidateAll after login process
  // LoginFilter 700: getNodeId on login
  // Jetty code: removeSession and invalidateAll multiple times on shutdown and then doStop
  private static class TestServlet extends HttpServlet {

    @Override
    public void doGet(HttpServletRequest request,
        HttpServletResponse response) throws IOException {
      switch (request.getServletPath()) {
        case "/newSession":
          request.getSession();
//          response.setStatus(HttpServletResponse.SC_OK);
          break;
        case "/existingSession":
          checkForValidSession(request, response);
          break;
        case "/invalidateSession":
          HttpSession session = checkForValidSession(request, response);

          session.invalidate();
//          response.setStatus(HttpServletResponse.SC_OK);
      }
    }

    private HttpSession checkForValidSession(HttpServletRequest request,  HttpServletResponse response)
        throws IOException {
      HttpSession session = request.getSession(false);
      if (session == null)
        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No valid session found");

      return session;
    }
  }
}
