package outbackcdx;

import outbackcdx.NanoHTTPD.IHTTPSession;
import outbackcdx.NanoHTTPD.Method;
import outbackcdx.NanoHTTPD.Response;
import outbackcdx.auth.Authorizer;
import outbackcdx.auth.Permission;
import outbackcdx.auth.Permit;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static outbackcdx.Json.GSON;
import static outbackcdx.NanoHTTPD.Response.Status.*;

class Web {

    interface Handler {
        Response handle(Request request) throws Exception;
    }

    static class Server extends NanoHTTPD {
        private final Handler handler;
        private final Authorizer authorizer;

        Server(ServerSocket socket, Handler handler, Authorizer authorizer) {
            super(socket);
            this.handler = handler;
            this.authorizer = authorizer;
        }

        @Override
        public Response serve(IHTTPSession session) {
            try {
                String authnHeader = session.getHeaders().getOrDefault("authorization", "");
                Permit permit = authorizer.verify(authnHeader);
                Request request = new Request(session, permit);
                return handler.handle(request);
            } catch (Web.ResponseException e) {
                return e.response;
            } catch (Exception e) {
                e.printStackTrace();
                return new Response(INTERNAL_ERROR, "text/plain", e.toString() + "\n");
            }
        }
    }

    static class Request {
        private final IHTTPSession session;
        private final Permit permit;

        Request(IHTTPSession session, Permit permit) {
            this.session = session;
            this.permit = permit;
        }

        public Method method() {
            return session.getMethod();
        }

        public String path() {
            return session.getUri();
        }

        public Map<String, String> params() {
            return session.getParms();
        }

        public String param(String name) {
            return session.getParms().get(name);
        }

        public String param(String name, String defaultValue) {
            return session.getParms().getOrDefault(name, defaultValue);
        }

        public String mandatoryParam(String name) throws ResponseException {
            String value = param(name);
            if (value == null) {
                throw new Web.ResponseException(badRequest("missing mandatory parameter: " + name));
            }
            return value;
        }

        public String header(String name) {
            return session.getHeaders().get(name);
        }

        public InputStream inputStream() {
            return session.getInputStream();
        }

        public boolean hasPermission(Permission permission) {
            return permit.permissions.contains(permission);
        }

        public String username() {
            return permit.username;
        }
    }

    public static class ResponseException extends Exception {
        final Response response;

        ResponseException(Response response) {
            this.response = response;
        }
    }

    private static String guessType(String file) {
        switch (file.substring(file.lastIndexOf('.') + 1)) {
            case "css":
                return "text/css";
            case "html":
                return "text/html";
            case "js":
                return "application/javascript";
            case "json":
                return "application/json";
            case "svg":
                return "image/svg+xml";
            default:
                throw new IllegalArgumentException("Unknown file type: " + file);
        }
    }

    static Handler serve(String file) {
        URL url = Web.class.getResource(file);
        if (url == null) {
            throw new IllegalArgumentException("No such resource: " + file);
        }
        return req -> new Response(OK, guessType(file), url.openStream());
    }

    static Response jsonResponse(Object data) {
        Response response =  new Response(OK, "application/json", GSON.toJson(data));
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    static Response notFound() {
        return new Response(NOT_FOUND, "text/plain", "Not found\n");
    }

    static Response forbidden(String permission) {
        return new Response(FORBIDDEN, "text/plain", "Permission '" + permission + "' is required for this action.\n");
    }

    static Response badRequest(String message) {
        return new Response(BAD_REQUEST, "text/plain", message);
    }

    static class Router implements Handler {
        private final List<Route> routes = new ArrayList<>();

        @Override
        public Response handle(Request request) throws Exception {
            for (Route route : routes) {
                Response result = route.handle(request);
                if (result != null) {
                    return result;
                }
            }
            return Web.notFound();
        }

        public Router on(Method method, String pathPattern, Handler handler, Permission permission) {
            routes.add(new Route(method, pathPattern, handler, permission));
            return this;
        }

        public Router on(Method method, String pathPattern, Handler handler) {
            return on(method, pathPattern, handler, null);
        }
    }

    private static class Route {
        private final static Pattern KEY_PATTERN = Pattern.compile("<([a-z_][a-zA-Z0-9_]*)(?::([^>]*))?>");

        private final Method method;
        private final Handler handler;
        private final String pattern;
        private final Pattern re;
        private final List<String> keys = new ArrayList<>();
        private final Permission permission;

        Route(Method method, String pattern, Handler handler, Permission permission) {
            this.method = method;
            this.handler = handler;
            this.pattern = pattern;
            this.permission = permission;
            this.re = compile();
        }

        private Pattern compile() {
            StringBuilder out = new StringBuilder();
            Matcher m = KEY_PATTERN.matcher(pattern);
            int pos = 0;
            while (m.find(pos)) {
                String key = m.group(1);
                String regex = m.group(2);
                if (regex == null) {
                    regex = "[^/,;?]+";
                }

                out.append(Pattern.quote(pattern.substring(pos, m.start())));
                out.append('(').append(regex).append(')');

                keys.add(key);
                pos = m.end();
            }

            out.append(Pattern.quote(pattern.substring(pos)));
            return Pattern.compile(out.toString());
        }

        public Response handle(Request request) throws Exception {
            if (method != null && method != request.method()) {
                return null;
            }

            Matcher match = re.matcher(request.path());
            if (!match.matches()) {
                return null;
            }

            if (permission != null && !request.hasPermission(permission)) {
                return Web.forbidden(permission.name().toLowerCase());
            }

            for (int i = 0; i < match.groupCount(); i++) {
                request.params().put(keys.get(i), match.group(i + 1));
            }

            return handler.handle(request);
        }
    }

}
