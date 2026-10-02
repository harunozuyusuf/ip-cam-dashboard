import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import com.sun.net.httpserver.*;

public class EventLogTest {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    static long count(String type){return Main.readTsv(Main.todayLog()).stream().filter(r->r[4].equals(type)).count();}
    static String record(LocalDate day,String type){return day.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))+" 12:00:00\tTest\t127.0.0.1\tNVR\t"+type+"\tONLINE\tOFFLINE\t0\tTest\n";}
    static HttpResponse<String> request(int port,String path,String body)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(body!=null)builder.POST(HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    public static void main(String[]args)throws Exception{
        HttpServer server=null;
        try{
            Files.createDirectories(Main.LOG_DIR);Files.createDirectories(Main.THUMB_DIR);Main.ensureConfig();Main.monitoring.set(false);
            Main.Camera c=new Main.Camera();c.name="Test Camera";c.ip="127.0.0.1";c.server="NVR";c.mac="AA:BB:CC:DD:EE:FF";
            long now=System.currentTimeMillis();c.macCheckedAt=now;Main.cameras.add(c);
            Main.applyProbe(c,1,now);check(count("ONLINE")==1,"Initial online event");
            for(int i=0;i<1000;i++)Main.applyProbe(c,1,now+i);
            check(Main.readTsv(Main.todayLog()).size()==1,"No continuous successful ping logs");
            Main.applyProbe(c,-1,now+2000);Main.applyProbe(c,-1,now+61000);
            check(count("OFFLINE")==1 && count("OFFLINE_ALERT")==0,"Offline transition and below threshold");
            Main.applyProbe(c,-1,now+62000);Main.applyProbe(c,-1,now+63000);
            check(count("OFFLINE_ALERT")==1,"60-second alert once");
            Main.persistState();Main.Camera restored=new Main.Camera();restored.ip=c.ip;Main.applyState(Main.loadPersistedStates().get(c.ip),restored);
            check(restored.alert,"Alert survives restart");
            Main.applyProbe(restored,-1,now+64000);check(count("OFFLINE_ALERT")==1,"No alert duplication after restart");
            Main.applyProbe(c,1,now+65000);Main.applyProbe(c,1,now+66000);check(count("RECOVERED")==1,"Recovery once");
            Main.ServerState ss=new Main.ServerState();ss.server="NVR";ss.serverIp="127.0.0.1";ss.status="OFFLINE";
            Main.recordServerState(ss);Main.recordServerState(ss);check(count("SERVER_OFFLINE")==1,"Server outage once");
            Main.ServerState up=new Main.ServerState();up.server="NVR";up.status="ONLINE";Main.recordServerState(up);Main.recordServerState(up);
            check(count("SERVER_RECOVERED")==1,"Server recovery once");
            Main.applyProbe(c,-1,now);c.offlineSince=LocalDate.now().minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();c.alert=true;
            Main.monitorDay=LocalDate.now().minusDays(1);Main.handleDayRollover();Main.handleDayRollover();
            check(count("DAY_RESET")==1 && c.status.equals("OFFLINE") && !c.alert,"Day reset remains once and preserves offline state");
            check(c.offlineSince==LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),"Day reset at midnight");
            Main.logError("Test",new IllegalStateException("private token"));check(count("SYSTEM_ERROR")==1,"System error event");
            check(!Files.readString(Main.todayLog()).contains("private token"),"No sensitive exception text");
            try{Main.sendEmail(new Properties(),"Test", "body");throw new AssertionError("Expected mail failure");}catch(IllegalArgumentException expected){}
            check(count("MAIL_FAILED")==1,"Mail failure recorded without sending mail");
            LocalDate today=LocalDate.now();
            for(int age:new int[]{1,30,31})Files.writeString(Main.LOG_DIR.resolve("events_"+today.minusDays(age)+".tsv"),Main.EVENT_HEADER+record(today.minusDays(age),"OLD_"+age));
            Path oldZip=Main.LOG_DIR.resolve("ping_"+today.minusDays(32)+".tsv");Files.writeString(oldZip,"header\nold\n");Main.archiveLog(oldZip);
            Path ping=Main.LOG_DIR.resolve("ping_"+today.minusDays(1)+".tsv");Files.writeString(ping,"header\nlegacy ping\n");
            String legacy=Main.EVENT_HEADER+record(today.minusDays(2),"LEGACY")+record(today.minusDays(35),"EXPIRED");
            Files.writeString(Main.EVENT_FILE,legacy.replace(today.minusDays(2).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))+" 12:00:00",today.minusDays(2)+"T12:00:00"));
            Main.cleanupOldLogs();Main.cleanupOldLogs();
            check(Files.exists(Main.todayLog()),"Today remains uncompressed");
            check(Files.exists(Main.LOG_DIR.resolve("events_"+today.minusDays(1)+".tsv.zip")),"Yesterday compressed");
            check(!Files.exists(Main.LOG_DIR.resolve("events_"+today.minusDays(1)+".tsv")),"Source removed after successful compression");
            check(Files.exists(Main.LOG_DIR.resolve("events_"+today.minusDays(30)+".tsv.zip")),"Exactly 30 days retained");
            check(!Files.exists(Main.LOG_DIR.resolve("events_"+today.minusDays(31)+".tsv")),"31-day raw deleted");
            check(!Files.exists(oldZip.resolveSibling(oldZip.getFileName()+".zip")),"Old archive deleted");
            check(Files.exists(ping.resolveSibling(ping.getFileName()+".zip")),"Legacy ping compressed");
            check(!Files.exists(Main.EVENT_FILE),"Legacy monolithic event file migrated");
            check(Main.readEventHistory().stream().filter(r->r[4].equals("LEGACY")).count()==1,"Legacy migration idempotent and archives readable");
            check(Main.readEventHistory().stream().noneMatch(r->r[4].equals("EXPIRED")),"Legacy retention enforced");
            check(Main.eventPreviewJson().contains("OLD_30") && !Main.logPreviewJson().contains("OLD_30"),"History versus today preview");
            check(Files.size(Main.buildLogXlsx())>0 && Files.size(Main.buildEventsXlsx())>0,"Event Excel exports");
            for(String[] row:Main.readEventHistory())check(row[0].matches("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2}"),"Date format");
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",Main::route);server.start();int port=server.getAddress().getPort();
            check(request(port,"/api/status",null).statusCode()==200,"Status API");long before=count("MANAGEMENT");
            request(port,"/api/settings/email",null);check(count("MANAGEMENT")==before,"Read-only settings not logged");
            request(port,"/api/settings/email","{\"enabled\":false,\"recipients\":\"\",\"offline_minutes\":60}");
            check(count("MANAGEMENT")==before+1,"Settings write audited");
            var saved=request(port,"/api/camera/save","{\"name\":\"Managed Camera\",\"ip\":\"127.0.0.2\",\"server\":\"NVR\",\"enabled\":false}");
            check(saved.statusCode()==200 && count("CAMERA_CREATED")==1,"Camera creation audited");
            String id=Main.jsonValue(saved.body(),"id");request(port,"/api/camera/toggle","{\"id\":\""+id+"\"}");check(count("CAMERA_ENABLED")==1,"Enable audited");
            request(port,"/api/camera/delete","{\"id\":\""+id+"\"}");check(count("CAMERA_DELETED")==1,"Delete audited");
            request(port,"/api/camera/delete","{\"id\":\"missing\"}");check(count("SYSTEM_ERROR")==2,"API errors logged");
            check(request(port,"/api/events-preview",null).body().contains("CAMERA_CREATED"),"History API includes management");
            check(request(port,"/api/log-preview",null).body().contains("OFFLINE_ALERT"),"Daily API includes alerts");
            var export=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/events")).build(),HttpResponse.BodyHandlers.ofByteArray());
            check(export.statusCode()==200 && export.body()[0]=='P' && export.body()[1]=='K',"Excel HTTP download");
            check(!Files.exists(Main.DATA.resolve("kamera_olaylari.xlsx")),"No unbounded export copy retained");
            Files.writeString(Main.EVENT_FILE,"header\ninvalid row\n");
            try{Main.cleanupOldLogs();throw new AssertionError("Invalid migration should fail");}catch(java.io.IOException expected){}
            check(Files.exists(Main.EVENT_FILE),"Malformed legacy source preserved");Files.delete(Main.EVENT_FILE);
            System.out.println("PASS: "+checks+" assertions; no external camera or mail requests.");
        }finally{if(server!=null)server.stop(0);Main.scheduler.shutdownNow();Main.pool.shutdownNow();}
    }
}
