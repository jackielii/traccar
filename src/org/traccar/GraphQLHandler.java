package org.traccar;

import com.ning.http.client.*;
import com.ning.http.client.cookie.Cookie;
import com.ning.http.client.cookie.CookieDecoder;
import org.traccar.helper.Log;
import org.traccar.model.Position;

import javax.servlet.http.HttpServletResponse;

public class GraphQLHandler extends WebDataHandler {
  private String postUrl;
  private String loginRoute;
  private Cookie sessionCookie;

  public GraphQLHandler(
      String postUrl, String postBody, String loginRoute, String username, String password) {
    super(postBody);
    this.postUrl = postUrl;
    if (loginRoute != null) {
      this.loginRoute = loginRoute.replace("{username}", username).replace("{password}", password);
    }
  }

  private synchronized void tryLogin() {
    String loginBody = "{ \"query\":\"" + loginRoute.replace("\"", "\\\"") + "\"}";
    ListenableFuture<Object> execute =
        Context.getAsyncHttpClient()
            .preparePost(postUrl)
            .setBody(loginBody)
            .execute(
                new AsyncHandler<Object>() {

                  @Override
                  public void onThrowable(Throwable throwable) {
                    Log.error(throwable.getMessage());
                  }

                  @Override
                  public STATE onBodyPartReceived(HttpResponseBodyPart httpResponseBodyPart)
                      throws Exception {
                    // we don't care about the response body
                    return STATE.ABORT;
                  }

                  @Override
                  public STATE onStatusReceived(HttpResponseStatus httpResponseStatus)
                      throws Exception {
                    if (httpResponseStatus.getStatusCode() != HttpServletResponse.SC_OK) {
                      Log.error("log in failed");
                      return STATE.ABORT;
                    }
                    return STATE.CONTINUE;
                  }

                  @Override
                  public STATE onHeadersReceived(HttpResponseHeaders httpResponseHeaders)
                      throws Exception {
                    String value = httpResponseHeaders.getHeaders().getFirstValue("Set-Cookie");
                    if (value != null) {
                      sessionCookie = CookieDecoder.decode(value);
                    }
                    return STATE.CONTINUE;
                  }

                  @Override
                  public Object onCompleted() throws Exception {
                    Log.debug("logged in to graphql");
                    return "";
                  }
                });
  }

  @Override
  protected Position handlePosition(Position position) {
    final AsyncHttpClient.BoundRequestBuilder requestBuilder =
        Context.getAsyncHttpClient().preparePost(postUrl);
    String insertMutation = formatRequest(position);
    if (sessionCookie != null) {
      requestBuilder.addCookie(sessionCookie);
    }
    insertMutation = "{ \"query\":\"" + insertMutation.replace("\"", "\\\"") + "\"}";
    requestBuilder.setBody(insertMutation);

    requestBuilder.execute(
        new AsyncHandler<Object>() {
          @Override
          public void onThrowable(Throwable throwable) {
            Log.error(throwable.getMessage());
          }

          @Override
          public STATE onBodyPartReceived(HttpResponseBodyPart httpResponseBodyPart)
              throws Exception {
            // we don't care about the response body
            return STATE.ABORT;
          }

          @Override
          public STATE onStatusReceived(HttpResponseStatus httpResponseStatus) throws Exception {
            switch (httpResponseStatus.getStatusCode()) {
              case HttpServletResponse.SC_UNAUTHORIZED:
                tryLogin();
                break;
              case HttpServletResponse.SC_OK:
                break;
              default:
                Log.error("unhandled response: " + httpResponseStatus);
                break;
            }
            return STATE.CONTINUE;
          }

          @Override
          public STATE onHeadersReceived(HttpResponseHeaders httpResponseHeaders) throws Exception {
            return STATE.CONTINUE;
          }

          @Override
          public Object onCompleted() throws Exception {
            return "";
          }
        });

    return position;
  }
}
