import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;

class MockExchange extends HttpExchange {
    final URI uri; final String method; final InputStream input;
    final ByteArrayOutputStream output=new ByteArrayOutputStream();
    final Headers requestHeaders=new Headers(),responseHeaders=new Headers();
    int code=-1;
    MockExchange(String path,String body){uri=URI.create(path);method=body==null?"GET":"POST";input=new ByteArrayInputStream((body==null?"":body).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public Headers getRequestHeaders(){return requestHeaders;}
    public Headers getResponseHeaders(){return responseHeaders;}
    public URI getRequestURI(){return uri;}
    public String getRequestMethod(){return method;}
    public HttpContext getHttpContext(){return null;}
    public void close(){}
    public InputStream getRequestBody(){return input;}
    public OutputStream getResponseBody(){return output;}
    public void sendResponseHeaders(int c,long length){code=c;}
    public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",1);}
    public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",2);}
    public int getResponseCode(){return code;}
    public String getProtocol(){return "HTTP/1.1";}
    public Object getAttribute(String name){return null;}
    public void setAttribute(String name,Object value){}
    public void setStreams(InputStream in,OutputStream out){}
    public HttpPrincipal getPrincipal(){return null;}
}
