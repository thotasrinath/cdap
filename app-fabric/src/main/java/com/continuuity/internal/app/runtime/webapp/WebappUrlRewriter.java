package com.continuuity.internal.app.runtime.webapp;

import com.continuuity.common.http.core.UrlRewriter;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;

/**
 * Rewrites incoming webapp URLs as Gateway URLs, if it is a Gateway call.
 * Otherwise, it resolves the jar path for the requested file.
 */
public class WebappUrlRewriter implements UrlRewriter {
  private final JarHttpHandler jarHttpHandler;

  public WebappUrlRewriter(JarHttpHandler jarHttpHandler) {
    this.jarHttpHandler = jarHttpHandler;
  }

  @Override
  public void rewrite(HttpRequest request) {
    String hostHeader = HttpHeaders.getHost(request);
    if (hostHeader == null) {
      return;
    }

    String path = jarHttpHandler.getServePath(hostHeader, request.getUri());
    if (path != null) {
      request.setUri(path);
    }
  }
}
