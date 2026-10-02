import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import java.util.zip.*;

public class Main {
    static final String VERSION = "J.3.3";
    static final int DEFAULT_PORT = 5000;
    static final int MAX_PORT = 5010;
    static final int CHECK_INTERVAL_SECONDS = 3;
    static final int OFFLINE_ALERT_SECONDS = 60;
    static final int ONLINE_HIDE_SECONDS = 180;
    static final int MAC_REFRESH_SECONDS = 1800;
    static final int RETENTION_DAYS = 30;
    static final int MAX_WORKERS = 32;
    static final Path BASE = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    static final Path DATA = BASE.resolve("data");
    static final Path XLSX = DATA.resolve("kamera_listesi.xlsx");
    static final Path CAMERAS_CFG = DATA.resolve("cameras.properties");
    static final Path UI = BASE.resolve("ui.html");
    static final Path CONFIG = DATA.resolve("config.properties");
    static final Path STATE = DATA.resolve("camera_state.properties");
    static final Path LOG_DIR = DATA.resolve("logs");
    static final Path EVENT_FILE = DATA.resolve("events.tsv");
    static final Path THUMB_DIR = DATA.resolve("thumbnails");
    static final Path THUMB_CFG = DATA.resolve("thumbnails.properties");
    static final Path GOOGLE_CREDS = DATA.resolve("credentials.json");
    static final Path PID_FILE = DATA.resolve("monitor.pid");
    static final Path PORT_FILE = DATA.resolve("monitor.port");
    static final Path BACKUP_TMP = DATA.resolve("backup_tmp");
    static final Path SYSTEM_MAIL_KEY_FILE = DATA.resolve("system_mail.key");
    static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    static final CopyOnWriteArrayList<Camera> cameras = new CopyOnWriteArrayList<>();
    static final ConcurrentHashMap<String,ServerState> servers = new ConcurrentHashMap<>();
    static final ExecutorService pool = Executors.newFixedThreadPool(MAX_WORKERS);
    static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    static final AtomicBoolean monitoring = new AtomicBoolean(true);
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    static volatile long lastScan = 0;
    static volatile long lastExcelSync = 0;
    static volatile String uiHtml = "";
    static volatile int currentPort = DEFAULT_PORT;
    static volatile LocalDate monitorDay = LocalDate.now();
    // Sistem e-posta hesabı kullanıcı arayüzünden gizlidir.
    // Parola düz metin olarak saklanmaz; AES/GCM ile şifrelenmiş veri çözülerek yalnızca gönderim anında bellekte kullanılır.
    // Bu, JAR tersine mühendisliğine karşı mutlak koruma değildir; amaç düz metin parola saklamamaktır.
    static final String SYSTEM_MAIL_USER = "kameraizlemej3@gmail.com";
    static final String SYSTEM_SMTP_HOST = "smtp.gmail.com";
    static final int SYSTEM_SMTP_PORT = 587;

    static class Camera {
        String id=UUID.randomUUID().toString(), name="", ip="", mac="", region="", server="Tanımsız Sunucu", serverIp="";
        volatile boolean enabled=true;
        volatile String status="BEKLENİYOR";
        volatile Long ping=null;
        volatile long lastCheck=0,lastSuccess=0,offlineSince=0,onlineSince=0,macCheckedAt=0;
        volatile boolean alert=false, mailSent=false;
    }
    static class ServerState { String server="", serverIp="", status="BİLİNMİYOR"; Long ping=null; long lastCheck=0; }
    static class Part { String name="", filename="", contentType=""; byte[] data=new byte[0]; }

    // Açıklama: Veri klasörlerini hazırlar, kayıtları yükler ve yerel web sunucusu ile periyodik görevleri başlatır.
    public static void main(String[] args) throws Exception {
        Files.createDirectories(DATA); Files.createDirectories(LOG_DIR); Files.createDirectories(THUMB_DIR);
        ensureConfig();
        if (openExistingInstanceIfRunning()) return;
        if (!Files.exists(XLSX) && Files.exists(BASE.resolve("kamera_listesi.xlsx"))) Files.copy(BASE.resolve("kamera_listesi.xlsx"), XLSX);
        uiHtml = Files.exists(UI) ? Files.readString(UI, StandardCharsets.UTF_8) : "<h1>ui.html bulunamadı</h1>";
        cleanupSafe(); loadCameraStoreOrMigrate(); logSystem("SYSTEM_START", "İzleme uygulaması başlatıldı");
        HttpServer server = bindHttpServer();
        server.createContext("/", Main::route);
        server.setExecutor(Executors.newCachedThreadPool()); server.start();
        writeRuntimeFiles();
        scheduler.scheduleWithFixedDelay(Main::scanSafe, 0, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(Main::mailWatchSafe, 20, 60, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(Main::cleanupSafe, 1, 1, TimeUnit.MINUTES);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { logSystem("SYSTEM_STOP", "İzleme uygulaması kapatıldı"); saveCameraStore(); persistState(); Files.deleteIfExists(PID_FILE); Files.deleteIfExists(PORT_FILE); } catch(Exception ignored){} }));
        openBrowser(currentPort);
        System.out.println("IP Kamera İzleme "+VERSION+": http://127.0.0.1:"+currentPort);
    }

    // Açıklama: Yalnızca bu bilgisayardan erişilen 5000–5010 portlarından boş olan ilkini kullanır.
    static HttpServer bindHttpServer() throws IOException {
        IOException last=null;
        for(int p=DEFAULT_PORT;p<=MAX_PORT;p++){
            try{ HttpServer s=HttpServer.create(new InetSocketAddress("127.0.0.1",p),0); currentPort=p; return s; }
            catch(BindException e){ last=e; }
        }
        throw new BindException("5000-5010 aralığında boş yerel port bulunamadı. Son hata: "+(last==null?"":last.getMessage()));
    }

    // Açıklama: PID dosyasındaki süreç çalışıyorsa ikinci uygulamayı açmak yerine mevcut arayüzü gösterir.
    static boolean openExistingInstanceIfRunning(){
        try{
            if(!Files.exists(PID_FILE)) return false;
            long pid=Long.parseLong(Files.readString(PID_FILE).trim());
            Optional<ProcessHandle> ph=ProcessHandle.of(pid);
            if(ph.isPresent() && ph.get().isAlive()){
                int p=DEFAULT_PORT;
                if(Files.exists(PORT_FILE)) try{p=Integer.parseInt(Files.readString(PORT_FILE).trim());}catch(Exception ignored){}
                browseNow(p);
                return true;
            }
            Files.deleteIfExists(PID_FILE); Files.deleteIfExists(PORT_FILE);
        }catch(Exception ignored){}
        return false;
    }

    // Açıklama: Başlatma ve durdurma araçları için çalışan süreç numarasını ve seçilen portu kaydeder.
    static void writeRuntimeFiles(){
        try{Files.writeString(PID_FILE,String.valueOf(ProcessHandle.current().pid()),StandardCharsets.US_ASCII);Files.writeString(PORT_FILE,String.valueOf(currentPort),StandardCharsets.US_ASCII);}catch(Exception ignored){}
    }

    // Açıklama: Tarayıcıdan gelen API isteklerini ilgili işleme yönlendirir; yönetim işlemlerini ve hataları kaydeder.
    static void route(HttpExchange x) throws IOException {
        try {
            String p=x.getRequestURI().getPath();
            if (p.equals("/")) html(x,uiHtml);
            else if (p.equals("/api/status")) json(x,200,statusJson());
            else if (p.equals("/api/servers")) json(x,200,serversJson());
            else if (p.equals("/api/upload")) handleExcelUpload(x);
            else if (p.equals("/api/camera/save")) cameraSave(x);
            else if (p.equals("/api/camera/delete")) cameraDelete(x);
            else if (p.equals("/api/camera/toggle")) cameraToggle(x);
            else if (p.equals("/api/monitor-state")) json(x,200,"{\"running\":"+monitoring.get()+",\"interval\":"+CHECK_INTERVAL_SECONDS+",\"workers\":"+MAX_WORKERS+"}");
            else if (p.equals("/api/monitor-start")) { monitoring.set(true); scheduler.execute(Main::scanSafe); json(x,200,"{\"ok\":true,\"running\":true}"); }
            else if (p.equals("/api/monitor-stop")) { monitoring.set(false); json(x,200,"{\"ok\":true,\"running\":false}"); }
            else if (p.equals("/api/log-preview")) json(x,200,logPreviewJson());
            else if (p.equals("/api/events-preview")) json(x,200,eventPreviewJson());
            else if (p.equals("/api/log")) sendEventExport(x,false);
            else if (p.equals("/api/events")) sendEventExport(x,true);
            else if (p.equals("/api/current-excel")) { syncExcel(); sendFile(x,XLSX,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","kamera_listesi_guncel.xlsx"); }
            else if (p.equals("/api/backup")) backupDownload(x);
            else if (p.equals("/api/restore")) backupRestore(x);
            else if (p.equals("/api/settings/email")) emailSettings(x);
            else if (p.equals("/api/settings/email/test")) testEmail(x);
            else if (p.equals("/api/settings/system-mail")) systemMailSettings(x);
            else if (p.equals("/api/google/credentials")) googleCredentialsUpload(x);
            else if (p.equals("/api/google/connect")) googleConnect(x);
            else if (p.equals("/api/google/callback")) googleCallback(x);
            else if (p.equals("/api/google/disconnect")) googleDisconnect(x);
            else if (p.equals("/api/settings/thumbnails")) json(x,200,thumbnailSettingsJson());
            else if (p.equals("/api/settings/thumbnail")) thumbnailSetting(x);
            else if (p.equals("/api/settings/thumbnail-upload")) thumbnailUpload(x);
            else if (p.startsWith("/api/thumbnail/")) thumbnailGet(x, URLDecoder.decode(p.substring("/api/thumbnail/".length()),StandardCharsets.UTF_8));
            else json(x,404,"{\"ok\":false,\"error\":\"Bulunamadı\"}");
        auditRequest(x);
        } catch(Exception e) { logError("HTTP " + x.getRequestURI().getPath(),e); e.printStackTrace(); json(x,500,"{\"ok\":false,\"error\":"+js(e.getMessage())+"}"); }
    }

    // Açıklama: İzleme açıksa tarama yapar; hata oluşursa periyodik görevin sonlanmaması için hatayı yakalar.
    static void scanSafe(){ try { if(monitoring.get()) scanAll(); } catch(Exception e){logError("Tarama",e);e.printStackTrace();} }
    // Açıklama: Aktif kameraları iş havuzunda kontrol eder; ardından sunucuları ve kalıcı durum dosyasını günceller.
    static void scanAll() throws Exception {
        handleDayRollover();
        long start=System.currentTimeMillis(); List<Future<?>> fs=new ArrayList<>();
        for(Camera c:cameras) if(c.enabled) fs.add(pool.submit(() -> scanCamera(c)));
        for(Future<?> f:fs) try{f.get(2500,TimeUnit.MILLISECONDS);}catch(Exception e){logError("Kamera tarama görevi",e);}
        updateServers(); lastScan=System.currentTimeMillis(); persistState();
        long elapsed=System.currentTimeMillis()-start; if(elapsed>3000) System.out.println("Tarama süresi: "+elapsed+" ms");
    }
    // Açıklama: Yeni günde aktif OFFLINE kameraların sayacını yerel gece yarısına taşır; durumu OFFLINE bırakıp DAY_RESET yazar.
    static synchronized void handleDayRollover() {
        LocalDate today=LocalDate.now();
        if(today.equals(monitorDay))return;
        LocalDate previous=monitorDay;
        long resetAt=today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if(resetAt>System.currentTimeMillis())resetAt=System.currentTimeMillis();
        int count=0;
        for(Camera c:cameras){
            if(c.enabled && c.status.equals("OFFLINE") && c.offlineSince>0){
                long oldDuration=Math.max(0,(resetAt-c.offlineSince)/1000);
                c.offlineSince=resetAt;
                c.alert=false;
                c.mailSent=false;
                logEvent(c,"DAY_RESET","OFFLINE","OFFLINE",oldDuration,
                    "Gün değişimi ("+previous.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))+
                    " -> "+today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))+
                    ") - offline süre sayacı sıfırlandı");
                count++;
            }
        }
        monitorDay=today;
        persistState();
        if(count>0)System.out.println("Gün değişimi: "+count+" offline kamera süre sayacı sıfırlandı.");
    }

    // Açıklama: Ağ erişim ölçümünü alır ve sonucu durum geçişlerini yöneten işleve iletir.
    static void scanCamera(Camera c) {
        long now=System.currentTimeMillis(); long ms=reach(c.ip,1200); applyProbe(c,ms,now);
    }
    // Açıklama: Başarılı ölçümde ONLINE, başarısız ölçümde OFFLINE durumunu uygular; her ping yerine geçişleri kaydeder.
    static void applyProbe(Camera c,long ms,long now) {
        String old=c.status; boolean wasAlert=c.alert; long priorOff=c.offlineSince; c.lastCheck=now;
        if(ms>=0){ c.status="ONLINE"; c.ping=ms; c.lastSuccess=now; c.alert=false; c.offlineSince=0;
            if(!old.equals("ONLINE") || c.onlineSince==0)c.onlineSince=now;
            if(!old.equals("ONLINE")){ long dur=priorOff>0?(now-priorOff)/1000:0; logEvent(c,old.equals("OFFLINE")?"RECOVERED":"ONLINE",old,"ONLINE",dur,"Kesinti "+dur+" sn sürdü"); c.mailSent=false; }
            if(c.mac.isBlank() || now-c.macCheckedAt>=MAC_REFRESH_SECONDS*1000L){ String mac=getMac(c.ip); c.macCheckedAt=now; if(mac!=null&&!mac.isBlank())c.mac=mac; }
        } else { c.status="OFFLINE"; c.ping=null; c.onlineSince=0; if(c.offlineSince==0)c.offlineSince=now; c.alert=(now-c.offlineSince)>=OFFLINE_ALERT_SECONDS*1000L;
            if(!old.equals("OFFLINE")) logEvent(c,"OFFLINE",old,"OFFLINE",0,"Ağ yanıtı alınamadı");
        }
        if(c.alert && !wasAlert) logEvent(c,"OFFLINE_ALERT",old,c.status,Math.max(0,(now-c.offlineSince)/1000),"Kamera en az 1 dakikadır OFFLINE");
    }
    // Açıklama: Java erişilebilirlik kontrolünün süresini milisaniye olarak döndürür; erişilemezse -1 kullanır.
    static long reach(String ip,int timeout){ try{long st=System.nanoTime();boolean ok=InetAddress.getByName(ip).isReachable(timeout);return ok?Math.max(1,(System.nanoTime()-st)/1_000_000):-1;}catch(Exception e){return -1;} }
    // Açıklama: İşletim sisteminin komşu/ARP tablosundan MAC adresini bulmaya çalışır; bulunamazsa boş değer döndürür.
    static String getMac(String ip){
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT); List<String> cmd;
        if(os.contains("win")) cmd=List.of("powershell.exe","-NoProfile","-WindowStyle","Hidden","-Command","arp -a "+ip);
        else if(os.contains("mac")) cmd=List.of("arp","-n",ip);
        else cmd=List.of("sh","-c","ip neigh show "+ip+" 2>/dev/null || arp -n "+ip+" 2>/dev/null");
        try{Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start();String out=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);p.waitFor(2,TimeUnit.SECONDS);Matcher m=Pattern.compile("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}").matcher(out);return m.find()?m.group().replace('-',':').toUpperCase(Locale.ROOT):"";}catch(Exception e){return "";}
    }
    // Açıklama: Aktif kameralardaki sunucu adlarını birleştirir ve tanımlı sunucu IP adreslerini kontrol eder.
    static void updateServers(){
        Map<String,String> uniq=new LinkedHashMap<>(); for(Camera c:cameras){if(!c.enabled)continue;String s=c.server.isBlank()?"Tanımsız Sunucu":c.server;if(!uniq.containsKey(s)||uniq.get(s).isBlank())uniq.put(s,c.serverIp);}
        List<Future<?>> fs=new ArrayList<>(); for(var e:uniq.entrySet()) fs.add(pool.submit(() -> {ServerState ss=new ServerState();ss.server=e.getKey();ss.serverIp=e.getValue();ss.lastCheck=System.currentTimeMillis(); if(ss.serverIp!=null&&!ss.serverIp.isBlank()){long ms=reach(ss.serverIp,1200);ss.status=ms>=0?"ONLINE":"OFFLINE";ss.ping=ms>=0?ms:null;}recordServerState(ss);}));
        for(Future<?> f:fs)try{f.get(2,TimeUnit.SECONDS);}catch(Exception e){logError("Sunucu tarama görevi",e);}
    }

    // Açıklama: Sunucu durumunu günceller; OFFLINE girişini ve OFFLINE durumundan çıkışı yalnızca değiştiğinde kaydeder.
    static void recordServerState(ServerState ss) {
        ServerState old=servers.put(ss.server,ss);
        String previous=old==null?"BİLİNMİYOR":old.status;
        if(!Objects.equals(previous,ss.status) && ("OFFLINE".equals(ss.status)||"OFFLINE".equals(previous))) {
            Camera subject=new Camera(); subject.name="NVR / Sunucu"; subject.server=ss.server; subject.ip=ss.serverIp;
            logEvent(subject,"OFFLINE".equals(ss.status)?"SERVER_OFFLINE":"SERVER_RECOVERED",previous,ss.status,0,"Sunucu erişim durumu değişti");
        }
    }

    // Açıklama: Öncelikle yerel kamera veritabanını okur; yoksa eski Excel listesini bu yapıya aktarır.
    static void loadCameraStoreOrMigrate() throws Exception {
        if(Files.exists(CAMERAS_CFG)) { loadCameraStore(); return; }
        if(Files.exists(XLSX)) {
            List<Camera> imported=Xlsx.read(XLSX);
            cameras.clear(); cameras.addAll(imported); saveCameraStore(); persistState();
            System.out.println(imported.size()+" kamera Excel'den J.3.0 yerel kamera veritabanına aktarıldı.");
        } else { cameras.clear(); saveCameraStore(); }
    }
    // Açıklama: Kamera tanımlarını ve önceki çalışma durumlarını yükler; pasif kameraları izleme dışında tutar.
    static synchronized void loadCameraStore() throws Exception {
        Properties p=new Properties(); try(Reader r=Files.newBufferedReader(CAMERAS_CFG,StandardCharsets.UTF_8)){p.load(r);}
        int count=(int)parseLong(p.getProperty("camera.count","0")); List<Camera> out=new ArrayList<>(); Map<String,Properties> persisted=loadPersistedStates();
        for(int i=0;i<count;i++){
            String x="camera."+i+"."; Camera c=new Camera(); c.id=p.getProperty(x+"id",UUID.randomUUID().toString()); c.name=p.getProperty(x+"name","").trim(); c.ip=p.getProperty(x+"ip","").trim();
            c.mac=p.getProperty(x+"mac","").trim().toUpperCase(Locale.ROOT); c.region=p.getProperty(x+"region","").trim(); c.server=p.getProperty(x+"server","Tanımsız Sunucu").trim(); if(c.server.isBlank())c.server="Tanımsız Sunucu";
            c.serverIp=p.getProperty(x+"serverIp","").trim(); c.enabled=Boolean.parseBoolean(p.getProperty(x+"enabled","true")); if(c.name.isBlank()||c.ip.isBlank())continue;
            Properties ps=persisted.get(c.ip); if(ps!=null)applyState(ps,c); if(!c.enabled){c.status="PASİF";c.ping=null;c.alert=false;}
            out.add(c);
        }
        cameras.clear(); cameras.addAll(out); scheduler.execute(Main::scanSafe);
    }
    // Açıklama: Kamera tanımlarını UTF-8 properties dosyasına yazar; bu dosya ana kamera veri kaynağıdır.
    static synchronized void saveCameraStore() throws Exception {
        Properties p=new Properties(); p.setProperty("camera.count",String.valueOf(cameras.size())); int i=0;
        for(Camera c:cameras){String x="camera."+(i++)+".";p.setProperty(x+"id",c.id);p.setProperty(x+"name",n(c.name));p.setProperty(x+"ip",n(c.ip));p.setProperty(x+"mac",n(c.mac));p.setProperty(x+"region",n(c.region));p.setProperty(x+"server",n(c.server));p.setProperty(x+"serverIp",n(c.serverIp));p.setProperty(x+"enabled",String.valueOf(c.enabled));}
        try(Writer w=Files.newBufferedWriter(CAMERAS_CFG,StandardCharsets.UTF_8)){p.store(w,"IP Kamera Monitor "+VERSION+" - primary camera store");}
    }
    // Açıklama: Düzenleme ve silme işlemlerinde kullanılan kalıcı kamera kimliğine göre kayıt arar.
    static Camera findCameraById(String id){for(Camera c:cameras)if(Objects.equals(c.id,id))return c;return null;}
    // Açıklama: Aynı IP adresinin birden fazla kamera kaydında kullanılmasını kontrol etmek için kayıt arar.
    static Camera findCameraByIp(String ip){for(Camera c:cameras)if(Objects.equals(c.ip,ip))return c;return null;}
    // Açıklama: Kamera alanlarını doğrular, IP çakışmasını kontrol eder ve ekleme/düzenleme işlemini kaydeder.
    static void cameraSave(HttpExchange x)throws Exception{
        if(!x.getRequestMethod().equalsIgnoreCase("POST")){json(x,405,"{}");return;} String b=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        String id=Optional.ofNullable(jsonValue(b,"id")).orElse("").trim(), name=Optional.ofNullable(jsonValue(b,"name")).orElse("").trim(), ip=Optional.ofNullable(jsonValue(b,"ip")).orElse("").trim();
        String region=Optional.ofNullable(jsonValue(b,"region")).orElse("").trim(), server=Optional.ofNullable(jsonValue(b,"server")).orElse("Tanımsız Sunucu").trim(), serverIp=Optional.ofNullable(jsonValue(b,"server_ip")).orElse("").trim();
        String thumbMode=Optional.ofNullable(jsonValue(b,"thumbnail_mode")).orElse("none"), thumbUrl=Optional.ofNullable(jsonValue(b,"thumbnail_url")).orElse("").trim(); boolean enabled=!"false".equalsIgnoreCase(Optional.ofNullable(jsonRaw(b,"enabled")).orElse("true"));
        if(name.isBlank())throw new IllegalArgumentException("Kamera adı zorunludur"); if(ip.isBlank())throw new IllegalArgumentException("IP adresi zorunludur");
        try{InetAddress.getByName(ip);}catch(Exception e){throw new IllegalArgumentException("Geçersiz IP/host adresi: "+ip);} Camera dup=findCameraByIp(ip); Camera c=id.isBlank()?null:findCameraById(id); if(dup!=null&&dup!=c)throw new IllegalArgumentException("Bu IP adresi başka bir kamerada kayıtlı: "+ip);
        boolean created=c==null; String oldIp=c==null?"":c.ip; if(c==null){c=new Camera();c.id=UUID.randomUUID().toString();cameras.add(c);} c.name=name;c.ip=ip;c.region=region;c.server=server.isBlank()?"Tanımsız Sunucu":server;c.serverIp=serverIp;c.enabled=enabled;
        if(!enabled){c.status="PASİF";c.ping=null;c.alert=false;c.offlineSince=0;c.onlineSince=0;} else if(c.status.equals("PASİF"))c.status="BEKLENİYOR";
        if(!oldIp.isBlank()&&!oldIp.equals(ip))migrateThumbKey(oldIp,ip); Properties tp=loadThumbConfig();String k=ipKey(ip);tp.setProperty(k+".mode",thumbMode);tp.setProperty(k+".url",thumbUrl);saveThumbConfig(tp);
        saveCameraStore();persistState();logEvent(c,created?"CAMERA_CREATED":"CAMERA_UPDATED","",c.status,0,"Kamera kaydı kaydedildi");scheduler.execute(Main::scanSafe);json(x,200,"{\"ok\":true,\"id\":"+js(c.id)+",\"item\":"+cameraJson(c)+"}");
    }
    // Açıklama: Kamerayı ve ona bağlı görsel ayarlarını kaldırır; silme olayını loga ekler.
    static void cameraDelete(HttpExchange x)throws Exception{
        if(!x.getRequestMethod().equalsIgnoreCase("POST")){json(x,405,"{}");return;}String b=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);String id=jsonValue(b,"id");Camera c=findCameraById(id);if(c==null)throw new IllegalArgumentException("Kamera bulunamadı");cameras.remove(c);removeThumbForIp(c.ip);saveCameraStore();persistState();logEvent(c,"CAMERA_DELETED",c.status,"",0,"Kamera silindi");json(x,200,"{\"ok\":true}");
    }
    // Açıklama: İzlemeyi kamera bazında açar veya kapatır; kapatırken geçici süre ve uyarı alanlarını sıfırlar.
    static void cameraToggle(HttpExchange x)throws Exception{
        if(!x.getRequestMethod().equalsIgnoreCase("POST")){json(x,405,"{}");return;}String b=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);Camera c=findCameraById(jsonValue(b,"id"));if(c==null)throw new IllegalArgumentException("Kamera bulunamadı");c.enabled=!c.enabled;if(!c.enabled){c.status="PASİF";c.ping=null;c.alert=false;c.offlineSince=0;c.onlineSince=0;}else c.status="BEKLENİYOR";saveCameraStore();persistState();logEvent(c,c.enabled?"CAMERA_ENABLED":"CAMERA_DISABLED","",c.status,0,"Kamera izleme durumu değiştirildi");scheduler.execute(Main::scanSafe);json(x,200,"{\"ok\":true,\"enabled\":"+c.enabled+"}");
    }
    // Açıklama: Kameranın IP adresi değişince görsel ayarlarını yeni IP anahtarına taşır.
    static void migrateThumbKey(String oldIp,String newIp)throws Exception{Properties p=loadThumbConfig();String a=ipKey(oldIp),b=ipKey(newIp);for(String f:List.of("mode","url","file")){String v=p.getProperty(a+"."+f);if(v!=null)p.setProperty(b+"."+f,v);p.remove(a+"."+f);}saveThumbConfig(p);}
    // Açıklama: Kamera görsel dosyasını ve ilgili görsel yapılandırma alanlarını kaldırır.
    static void removeThumbForIp(String ip)throws Exception{Properties p=loadThumbConfig();String k=ipKey(ip),file=p.getProperty(k+".file","");if(!file.isBlank())try{Files.deleteIfExists(THUMB_DIR.resolve(file));}catch(Exception ignored){}for(String f:List.of("mode","url","file"))p.remove(k+"."+f);saveThumbConfig(p);}
    // Açıklama: Excel listesini kamera veritabanına aktarırken eşleşen kameraların çalışma durumunu korur.
    static void importExcelToStore(boolean fullRefresh) throws Exception {
        Map<String,Camera> old=new HashMap<>(); for(Camera c:cameras)old.put(c.ip,c); Map<String,Properties> persisted=loadPersistedStates(); List<Camera> n=Xlsx.read(XLSX);
        for(Camera c:n){ Camera o=old.get(c.ip); Properties ps=persisted.get(c.ip); if(o!=null){c.id=o.id;c.enabled=o.enabled;copyRuntime(o,c);} else if(ps!=null)applyState(ps,c); }
        cameras.clear(); cameras.addAll(n); saveCameraStore(); if(fullRefresh)persistState(); scheduler.execute(Main::scanSafe);
    }
    // Açıklama: Liste yeniden yüklendiğinde mevcut kameranın ölçüm, süre ve bildirim durumunu yeni nesneye kopyalar.
    static void copyRuntime(Camera a,Camera b){b.mac=b.mac.isBlank()?a.mac:b.mac;b.status=a.status;b.ping=a.ping;b.lastCheck=a.lastCheck;b.lastSuccess=a.lastSuccess;b.offlineSince=a.offlineSince;b.onlineSince=a.onlineSince;b.macCheckedAt=a.macCheckedAt;b.alert=a.alert;b.mailSent=a.mailSent;}
    // Açıklama: Yeniden başlatmada kullanılmak üzere IP anahtarlarına göre kaydedilmiş kamera durumlarını okur.
    static Map<String,Properties> loadPersistedStates(){Map<String,Properties> out=new HashMap<>();if(!Files.exists(STATE))return out;try{Properties all=new Properties();try(InputStream in=Files.newInputStream(STATE)){all.load(new InputStreamReader(in,StandardCharsets.UTF_8));}for(String k:all.stringPropertyNames()){int dot=k.indexOf('.');if(dot<0)continue;String ip=decodeIpKey(k.substring(0,dot)),field=k.substring(dot+1);if(ip.isBlank())continue;out.computeIfAbsent(ip,z->new Properties()).setProperty(field,all.getProperty(k));}}catch(Exception ignored){}return out;}
    // Açıklama: Kalıcı durum alanlarını kameraya uygular; uyarı bayrağı aynı kesinti için tekrar kayıt oluşmasını önler.
    static void applyState(Properties p,Camera c){c.mac=c.mac.isBlank()?p.getProperty("mac",""):c.mac;c.status=p.getProperty("status","BEKLENİYOR");String v=p.getProperty("ping","");c.ping=v.isBlank()?null:parseLongObj(v);c.lastCheck=parseLong(p.getProperty("lastCheck","0"));c.lastSuccess=parseLong(p.getProperty("lastSuccess","0"));c.offlineSince=parseLong(p.getProperty("offlineSince","0"));c.onlineSince=parseLong(p.getProperty("onlineSince","0"));c.macCheckedAt=parseLong(p.getProperty("macCheckedAt","0"));c.mailSent=Boolean.parseBoolean(p.getProperty("mailSent","false"));c.alert=Boolean.parseBoolean(p.getProperty("alert","false"));}
    // Açıklama: Ölçüm zamanlarını, sayaçları ve bildirim bayraklarını yeniden başlatmalar arasında saklar.
    static synchronized void persistState(){try{Properties p=new Properties();for(Camera c:cameras){String x=ipKey(c.ip)+".";p.setProperty(x+"mac",n(c.mac));p.setProperty(x+"status",c.status);p.setProperty(x+"ping",c.ping==null?"":c.ping.toString());p.setProperty(x+"lastCheck",String.valueOf(c.lastCheck));p.setProperty(x+"lastSuccess",String.valueOf(c.lastSuccess));p.setProperty(x+"offlineSince",String.valueOf(c.offlineSince));p.setProperty(x+"onlineSince",String.valueOf(c.onlineSince));p.setProperty(x+"macCheckedAt",String.valueOf(c.macCheckedAt));p.setProperty(x+"mailSent",String.valueOf(c.mailSent));p.setProperty(x+"alert",String.valueOf(c.alert));}try(Writer w=Files.newBufferedWriter(STATE,StandardCharsets.UTF_8)){p.store(w,"IP Kamera Monitor "+VERSION);}}catch(Exception e){logError("Durum kaydı",e);System.err.println("Durum kaydı: "+e);}}

    static final String EVENT_HEADER="ts\tname\tip\tserver\tevent_type\told_status\tnew_status\tduration_seconds\tdetails\n";
    static final Pattern LOG_NAME=Pattern.compile("(?:events|legacy_events|ping)_(\\d{4}-\\d{2}-\\d{2})\\.tsv(?:\\.zip)?");
    // Açıklama: Dosya adında sıralanabilir yıl-ay-gün, kayıt içeriğinde ise G/A/Y tarih biçimi kullanılır.
    static Path todayLog(){return LOG_DIR.resolve("events_"+LocalDate.now()+".tsv");}
    // Açıklama: Günlük olay dosyasına tek satır ekler; eşzamanlı yazmaları synchronized ile sıralar.
    static synchronized void logEvent(Camera c,String type,String old,String neu,long dur,String details){
        try { Files.createDirectories(LOG_DIR); Path file=todayLog();boolean fresh=!Files.exists(file);
            try(BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND)){
                if(fresh)w.write(EVENT_HEADER);
                w.write(tsv(nowIso(),c==null?"Sistem":c.name,c==null?"":c.ip,c==null?"":c.server,type,old,neu,String.valueOf(dur),details)+"\n");
            }
        }catch(Exception e){System.err.println("Olay kaydı yazılamadı: "+e.getClass().getSimpleName());}
    }
    // Açıklama: Belirli bir kameraya bağlı olmayan olayları Sistem adıyla kaydeder.
    static void logSystem(String type,String details){logEvent(null,type,"","",0,details);}
    // Açıklama: Hata bağlamını ve istisna türünü yazar; hassas bilgi içerebilen istisna mesajını loga koymaz.
    static void logError(String context,Throwable error){logSystem("SYSTEM_ERROR",context+" — "+error.getClass().getSimpleName());}
    // Açıklama: Başarılı yönetim isteklerini kaydeder; sıradan ayar okuma istekleri olay üretmez.
    static void auditRequest(HttpExchange x){
        if(x.getResponseCode()<200 || x.getResponseCode()>=400)return;
        String path=x.getRequestURI().getPath();
        String operation=switch(path){
            case "/api/upload" -> "Excel kamera listesi içe aktarıldı";
            case "/api/restore" -> "Yapılandırma yedeği geri yüklendi";
            case "/api/monitor-start" -> "İzleme başlatıldı";
            case "/api/monitor-stop" -> "İzleme durduruldu";
            case "/api/settings/email" -> "E-posta ayarları değiştirildi";
            case "/api/settings/system-mail" -> "Sistem maili yapılandırıldı";
            case "/api/settings/thumbnail", "/api/settings/thumbnail-upload" -> "Kamera görseli ayarlandı";
            case "/api/google/credentials" -> "Google kimlik yapılandırması değiştirildi";
            case "/api/google/disconnect" -> "Google hesabı bağlantısı kaldırıldı";
            case "/api/google/callback" -> "Google hesabı bağlandı";
            case "/api/backup" -> "Yapılandırma yedeği indirildi";
            default -> null;
        };
        if(operation!=null && (!x.getRequestMethod().equalsIgnoreCase("GET") || Set.of("/api/backup","/api/google/callback","/api/google/disconnect").contains(path)))logSystem("MANAGEMENT",operation);
    }
    // Açıklama: Alanlardaki sekme ve satır sonlarını temizleyerek her olayın tek TSV satırında kalmasını sağlar.
    static String tsv(String...a){StringJoiner j=new StringJoiner("\t");for(String s:a)j.add(n(s).replace("\t"," ").replace("\r"," ").replace("\n"," "));return j.toString();}
    // Açıklama: Düz TSV veya ZIP içindeki ilk TSV girdisini okur; başlık satırını veri listesine eklemez.
    static List<String[]> readTsv(Path file){
        List<String[]> rows=new ArrayList<>();if(!Files.exists(file))return rows;
        try(InputStream raw=Files.newInputStream(file)){
            InputStream in=raw;
            if(file.toString().endsWith(".zip")){ZipInputStream zip=new ZipInputStream(raw);if(zip.getNextEntry()==null)return rows;in=zip;}
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
                reader.readLine();String line;while((line=reader.readLine())!=null)rows.add(line.split("\t",-1));
            }
        }catch(Exception e){logError("Log okuma: "+file.getFileName(),e);}
        return rows;
    }
    // Açıklama: Saklama süresindeki günlük olayları birleştirir; eski ping satırlarını ve çift arşiv kopyalarını dışarıda bırakır.
    static synchronized List<String[]> readEventHistory(){
        List<String[]> rows=new ArrayList<>();LocalDate cut=LocalDate.now().minusDays(RETENTION_DAYS);
        try(var paths=Files.list(LOG_DIR)){
            for(Path file:paths.sorted().toList()){
                String name=file.getFileName().toString();Matcher m=LOG_NAME.matcher(name);
                if(!m.matches() || name.startsWith("ping_") || LocalDate.parse(m.group(1)).isBefore(cut))continue;
                if(name.endsWith(".zip") && Files.exists(file.resolveSibling(name.substring(0,name.length()-4))))continue;
                rows.addAll(readTsv(file));
            }
        }catch(Exception e){logError("Olay geçmişi",e);}
        rows.sort(Comparator.comparing(a->LocalDateTime.parse(a[0],DT)));return rows;
    }
    // Açıklama: Bakım hatasını olay olarak kaydeder; sonraki planlı bakımın yeniden çalışmasına izin verir.
    static void cleanupSafe(){try{cleanupOldLogs();}catch(Exception e){logError("Log arşivleme / temizleme",e);}}
    // Açıklama: Önce eski olay yapısını taşır; 30 günden eski dosyaları siler, önceki günlerin TSV dosyalarını sıkıştırır.
    static synchronized void cleanupOldLogs()throws IOException{
        Files.createDirectories(LOG_DIR);migrateLegacyEvents();
        LocalDate today=LocalDate.now(),cut=today.minusDays(RETENTION_DAYS);
        try(var paths=Files.list(LOG_DIR)){
            for(Path file:paths.toList()){
                Matcher m=LOG_NAME.matcher(file.getFileName().toString());if(!m.matches() || !Files.isRegularFile(file))continue;
                LocalDate day=LocalDate.parse(m.group(1));
                if(day.isBefore(cut)){Files.deleteIfExists(file);continue;}
                if(day.isBefore(today) && file.toString().endsWith(".tsv"))archiveLog(file);
            }
        }
        // Eski log Excel çıktıları da temizlenir; kamera ve yapılandırma dışa aktarımları korunur.
        try(var paths=Files.list(DATA)){
            for(Path file:paths.toList()){
                Matcher m=Pattern.compile("kamera_log_(\\d{4}-\\d{2}-\\d{2})\\.xlsx").matcher(file.getFileName().toString());
                if(m.matches() && LocalDate.parse(m.group(1)).isBefore(cut))Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(DATA.resolve("kamera_olaylari.xlsx"));
    }
    // Açıklama: Geçici ZIP oluşturup tamamını okuyarak doğrular; kaynak TSV ancak arşiv yayımlandıktan sonra silinir.
    static void archiveLog(Path file)throws IOException{
        Path zip=file.resolveSibling(file.getFileName()+".zip"),tmp=file.resolveSibling(file.getFileName()+".zip.tmp");
        try{
            try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(tmp))){out.putNextEntry(new ZipEntry(file.getFileName().toString()));Files.copy(file,out);out.closeEntry();}
            try(ZipInputStream in=new ZipInputStream(Files.newInputStream(tmp))){if(in.getNextEntry()==null)throw new IOException("Boş arşiv");in.transferTo(OutputStream.nullOutputStream());}
            Files.move(tmp,zip,StandardCopyOption.REPLACE_EXISTING);Files.delete(file);
        }finally{Files.deleteIfExists(tmp);}
    }
    // Açıklama: Eski G/A/Y veya ISO tarihlerini okuyarak ortak tarih-saat nesnesine dönüştürür.
    static LocalDateTime legacyTime(String value){
        try{return LocalDateTime.parse(value,DT);}catch(java.time.format.DateTimeParseException e){return LocalDateTime.parse(value.replace(' ','T'));}
    }
    // Açıklama: Tek events.tsv dosyasını günlük dosyalara ayırır; aktarım başarısız olursa eski kaynak korunur.
    static void migrateLegacyEvents()throws IOException{
        if(!Files.exists(EVENT_FILE))return;
        Map<LocalDate,BufferedWriter> writers=new HashMap<>();Map<LocalDate,Path> staged=new HashMap<>();
        LocalDate cut=LocalDate.now().minusDays(RETENTION_DAYS);
        try{
            try(BufferedReader reader=Files.newBufferedReader(EVENT_FILE,StandardCharsets.UTF_8)){
                reader.readLine();String line;
                while((line=reader.readLine())!=null){
                    if(line.isBlank())continue;String[] row=line.split("\t",-1);if(row.length!=9)throw new IOException("Eski olay satırı geçersiz; kaynak korundu");
                    LocalDateTime date=legacyTime(row[0]);LocalDate day=date.toLocalDate();if(day.isBefore(cut))continue;
                    BufferedWriter writer=writers.get(day);
                    if(writer==null){Path tmp=LOG_DIR.resolve("legacy_events_"+day+".tsv.tmp");staged.put(day,tmp);writer=Files.newBufferedWriter(tmp,StandardCharsets.UTF_8);writers.put(day,writer);writer.write(EVENT_HEADER);}
                    row[0]=date.format(DT);writer.write(String.join("\t",row));writer.newLine();
                }
            }finally{for(BufferedWriter writer:writers.values())writer.close();}
            // Yeniden denemede eski günlükler değiştirilir; canlı olaylara eklenerek çift kayıt oluşturulmaz.
            for(var entry:staged.entrySet()){
                Path target=LOG_DIR.resolve("legacy_events_"+entry.getKey()+".tsv");
                Files.move(entry.getValue(),target,StandardCopyOption.REPLACE_EXISTING);Files.deleteIfExists(target.resolveSibling(target.getFileName()+".zip"));
            }
            Files.delete(EVENT_FILE);
        }catch(RuntimeException e){throw new IOException("Eski olay tarihleri taşınamadı; kaynak korundu",e);}
        finally{for(Path tmp:staged.values())Files.deleteIfExists(tmp);}
    }

    // Açıklama: Kamera durumunu arayüze hazırlar; mevcut filtre ONLINE kameraları 180 saniye sonra gizlemeye devam eder.
    static String cameraJson(Camera c){long now=System.currentTimeMillis();long off=c.offlineSince>0?(now-c.offlineSince)/1000:0,on=c.onlineSince>0?(now-c.onlineSince)/1000:0;boolean vis=c.enabled&&!(c.status.equals("ONLINE")&&on>=ONLINE_HIDE_SECONDS);return new StringBuilder("{").append("\"id\":").append(js(c.id)).append(",\"name\":").append(js(c.name)).append(",\"ip\":").append(js(c.ip)).append(",\"mac\":").append(js(c.mac)).append(",\"region\":").append(js(c.region)).append(",\"server\":").append(js(c.server)).append(",\"server_ip\":").append(js(c.serverIp)).append(",\"enabled\":").append(c.enabled).append(",\"status\":").append(js(c.enabled?c.status:"PASİF")).append(",\"ping\":").append(c.ping==null?"null":c.ping).append(",\"last_check\":").append(js(fmtTs(c.lastCheck))).append(",\"last_success\":").append(js(fmtTs(c.lastSuccess))).append(",\"offline_seconds\":").append(off).append(",\"online_seconds\":").append(on).append(",\"alert\":").append(c.enabled&&c.alert).append(",\"visible_problem\":").append(vis).append('}').toString();}
    // Açıklama: Kamera listesini tarayıcının kullandığı JSON dizisine dönüştürür.
    static String statusJson(){StringBuilder s=new StringBuilder("[");boolean first=true;for(Camera c:cameras){if(!first)s.append(',');first=false;s.append(cameraJson(c));}return s.append(']').toString();}
    // Açıklama: Sunucuları adlarına göre sıralayarak durum ve erişim sürelerini JSON olarak sunar.
    static String serversJson(){StringBuilder s=new StringBuilder("[");boolean first=true;List<ServerState> list=new ArrayList<>(servers.values());list.sort(Comparator.comparing(a->a.server));for(ServerState x:list){if(!first)s.append(',');first=false;s.append("{\"server\":").append(js(x.server)).append(",\"server_ip\":").append(js(x.serverIp)).append(",\"status\":").append(js(x.status)).append(",\"ping\":").append(x.ping==null?"null":x.ping).append(",\"last_check\":").append(js(fmtTs(x.lastCheck))).append('}');}return s.append(']').toString();}

    // Açıklama: Yüklenen Excel dosyasını okur ve kamera listesini içe aktarır; bu işlem mevcut listeyi yeniler.
    static void handleExcelUpload(HttpExchange x)throws Exception{if(!x.getRequestMethod().equalsIgnoreCase("POST")){json(x,405,"{}");return;}List<Part> parts=parseMultipart(x);Part file=parts.stream().filter(p->!p.filename.isBlank()).findFirst().orElse(null);if(file==null)throw new IOException("Excel dosyası bulunamadı");Path tmp=DATA.resolve("kamera_upload.xlsx");Files.write(tmp,file.data);List<Camera> test=Xlsx.read(tmp);Files.move(tmp,XLSX,StandardCopyOption.REPLACE_EXISTING);importExcelToStore(true);json(x,200,"{\"ok\":true,\"count\":"+test.size()+",\"items\":"+statusJson()+"}");}

    // Açıklama: Bildirim alıcılarını, etkinlik durumunu ve kesinti eşiğini okur veya kaydeder.
    static void emailSettings(HttpExchange x)throws Exception {
        Properties p=loadConfig();
        if(x.getRequestMethod().equalsIgnoreCase("GET")){
            String out="{\"enabled\":"+bool(p,"email.enabled",true)+",\"recipients\":"+js(p.getProperty("email.recipients",""))+",\"offline_minutes\":"+parseLong(p.getProperty("offline.minutes","60"))+",\"system_sender\":"+js(SYSTEM_MAIL_USER)+",\"system_mail_configured\":"+systemMailConfigured(p)+"}";
            json(x,200,out);return;
        }
        String b=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        setIfJson(p,b,"enabled","email.enabled");
        setIfJson(p,b,"recipients","email.recipients");
        setIfJson(p,b,"offline_minutes","offline.minutes");
        p.setProperty("email.method","system");
        saveConfig(p);
        json(x,200,"{\"ok\":true}");
    }
    // Açıklama: Mevcut mail ayarlarıyla gerçek bir test e-postası gönderir.
    static void testEmail(HttpExchange x)throws Exception{Properties p=loadConfig();sendEmail(p,"IP Kamera İzleme - Test","Bu, IP Kamera İzleme "+VERSION+" tarafından gönderilen test e-postasıdır.");json(x,200,"{\"ok\":true,\"method\":\"system\"}");}
    // Açıklama: Periyodik mail kontrolündeki hataları yakalayıp sistem olayı olarak kaydeder.
    static void mailWatchSafe(){try{mailWatch();}catch(Exception e){logError("E-posta kontrolü",e);System.err.println("E-posta kontrolü: "+e.getMessage());}}
    // Açıklama: Bildirim eşiğini geçen ve henüz mail gönderilmemiş kesintileri toplu e-posta olarak bildirir.
    static void mailWatch()throws Exception{Properties p=loadConfig();if(!bool(p,"email.enabled",true))return;long threshold=parseLong(p.getProperty("offline.minutes","60"))*60000L,now=System.currentTimeMillis();List<Camera>a=new ArrayList<>();for(Camera c:cameras)if(c.enabled&&c.status.equals("OFFLINE")&&c.offlineSince>0&&!c.mailSent&&now-c.offlineSince>=threshold)a.add(c);if(a.isEmpty())return;StringBuilder body=new StringBuilder();body.append(parseLong(p.getProperty("offline.minutes","60"))).append(" dakika veya daha uzun süredir haberleşmeyen kameralar:\n\n");for(Camera c:a)body.append(c.name).append(" | ").append(c.ip).append(" | ").append(c.server).append(" | Offline: ").append(formatDuration((now-c.offlineSince)/1000)).append('\n');sendEmail(p,"IP Kamera Offline Uyarısı",body.toString());for(Camera c:a)c.mailSent=true;persistState();}
    // Açıklama: Gönderimi yapar; başarıda MAIL_SENT, hatada MAIL_FAILED yazar ve hatayı çağırana iletir.
    static void sendEmail(Properties p,String subject,String body)throws Exception{
        try { String r=p.getProperty("email.recipients","").trim();if(r.isBlank())throw new IllegalArgumentException("Alıcı adresi girilmemiş");sendSystemGmail(p,subject,body);logSystem("MAIL_SENT",subject); }
        catch(Exception e){logSystem("MAIL_FAILED",subject+" — "+e.getClass().getSimpleName());throw e;}
    }

    // Açıklama: Şifreli parola alanlarının ve yerel anahtar dosyasının bulunup bulunmadığını kontrol eder.
    static boolean systemMailConfigured(Properties p){return !p.getProperty("system.mail.iv","").isBlank()&&!p.getProperty("system.mail.cipher","").isBlank()&&Files.exists(SYSTEM_MAIL_KEY_FILE);}
    // Açıklama: Mail parolasının şifrelenmesinde kullanılan yerel AES anahtarını okur veya oluşturur.
    static byte[] systemMailKey() throws Exception {
        if(Files.exists(SYSTEM_MAIL_KEY_FILE)){byte[] k=Base64.getDecoder().decode(Files.readString(SYSTEM_MAIL_KEY_FILE,StandardCharsets.US_ASCII).trim());if(k.length==32)return k;}
        byte[] k=new byte[32];new SecureRandom().nextBytes(k);Files.writeString(SYSTEM_MAIL_KEY_FILE,Base64.getEncoder().encodeToString(k),StandardCharsets.US_ASCII,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);return k;
    }
    // Açıklama: Uygulama parolasını doğrular ve rastgele başlangıç değeriyle AES/GCM kullanarak şifreler.
    static void saveSystemMailPassword(Properties p,String password) throws Exception {
        password=password==null?"":password.replaceAll("\\s+","");
        if(password.length()!=16)throw new IllegalArgumentException("Google Uygulama Şifresi 16 karakter olmalıdır");
        byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(systemMailKey(),"AES"),new GCMParameterSpec(128,iv));byte[] enc=cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        p.setProperty("system.mail.iv",Base64.getEncoder().encodeToString(iv));p.setProperty("system.mail.cipher",Base64.getEncoder().encodeToString(enc));saveConfig(p);
    }
    // Açıklama: Kaydedilmiş şifreli parolayı gönderim sırasında kullanılmak üzere bellekte çözer.
    static String systemMailPassword(Properties p) throws Exception {
        if(!systemMailConfigured(p))throw new IllegalStateException("Sistem maili henüz kurulmadı. Ayarlar > E-posta > Sistem Mail Kurulumu bölümünden 16 haneli Google Uygulama Şifresini kaydedin.");
        byte[] iv=Base64.getDecoder().decode(p.getProperty("system.mail.iv"));byte[] encrypted=Base64.getDecoder().decode(p.getProperty("system.mail.cipher"));
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(systemMailKey(),"AES"),new GCMParameterSpec(128,iv));return new String(cipher.doFinal(encrypted),StandardCharsets.UTF_8);
    }
    // Açıklama: Sistem mailinin yapılandırma durumunu döndürür veya uygulama parolasını kaydeder.
    static void systemMailSettings(HttpExchange x)throws Exception{
        Properties p=loadConfig();
        if(x.getRequestMethod().equalsIgnoreCase("GET")){json(x,200,"{\"ok\":true,\"sender\":"+js(SYSTEM_MAIL_USER)+",\"configured\":"+systemMailConfigured(p)+"}");return;}
        if(!x.getRequestMethod().equalsIgnoreCase("POST")){json(x,405,"{}");return;}
        String b=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),pass=Optional.ofNullable(jsonValue(b,"app_password")).orElse("");saveSystemMailPassword(p,pass);json(x,200,"{\"ok\":true,\"configured\":true}");
    }
    // Açıklama: SMTP görüşmesini yürütür ve STARTTLS üzerinden sistem hesabıyla e-posta gönderir.
    static void sendSystemGmail(Properties p,String subject,String body)throws Exception{
        String rcpts=p.getProperty("email.recipients","");
        String pass=systemMailPassword(p);
        Socket sock=new Socket(SYSTEM_SMTP_HOST,SYSTEM_SMTP_PORT);sock.setSoTimeout(15000);
        BufferedReader in=new BufferedReader(new InputStreamReader(sock.getInputStream(),StandardCharsets.UTF_8));BufferedWriter out=new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(),StandardCharsets.UTF_8));
        expect(in,220);smtpCmd(out,in,"EHLO IPCameraMonitor",250);smtpCmd(out,in,"STARTTLS",220);
        SSLSocket ssl=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(sock,SYSTEM_SMTP_HOST,SYSTEM_SMTP_PORT,true);ssl.startHandshake();sock=ssl;
        in=new BufferedReader(new InputStreamReader(sock.getInputStream(),StandardCharsets.UTF_8));out=new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(),StandardCharsets.UTF_8));smtpCmd(out,in,"EHLO IPCameraMonitor",250);
        smtpCmd(out,in,"AUTH LOGIN",334);smtpCmd(out,in,Base64.getEncoder().encodeToString(SYSTEM_MAIL_USER.getBytes(StandardCharsets.UTF_8)),334);smtpCmd(out,in,Base64.getEncoder().encodeToString(pass.getBytes(StandardCharsets.UTF_8)),235);
        smtpCmd(out,in,"MAIL FROM:<"+SYSTEM_MAIL_USER+">",250);for(String rr:rcpts.split("[,;]")){rr=rr.trim();if(!rr.isBlank())smtpCmd(out,in,"RCPT TO:<"+rr+">",250);}
        smtpCmd(out,in,"DATA",354);String msg="From: "+SYSTEM_MAIL_USER+"\r\nTo: "+rcpts+"\r\nSubject: =?UTF-8?B?"+Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8))+"?=\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"+body.replace("\n","\r\n")+"\r\n.\r\n";out.write(msg);out.flush();expect(in,250);try{smtpCmd(out,in,"QUIT",221);}catch(Exception ignored){}sock.close();
    }
    static void smtpCmd(BufferedWriter out,BufferedReader in,String s,int code)throws Exception{out.write(s+"\r\n");out.flush();expect(in,code);}static void expect(BufferedReader in,int code)throws Exception{String line=in.readLine();if(line==null||!line.startsWith(String.valueOf(code)))throw new IOException("SMTP: "+line);while(line.length()>3&&line.charAt(3)=='-'){line=in.readLine();if(line==null)break;}}

    // Açıklama: Yüklenen Google OAuth istemci dosyasındaki gerekli alanları doğrulayarak dosyayı saklar.
    static void googleCredentialsUpload(HttpExchange x)throws Exception{List<Part> ps=parseMultipart(x);Part f=ps.stream().filter(z->!z.filename.isBlank()).findFirst().orElse(null);if(f==null)throw new IOException("credentials.json bulunamadı");String s=new String(f.data,StandardCharsets.UTF_8);if(jsonValue(s,"client_id")==null||jsonValue(s,"client_secret")==null)throw new IllegalArgumentException("Geçerli Google OAuth istemci JSON dosyası değil");Files.write(GOOGLE_CREDS,f.data);json(x,200,"{\"ok\":true,\"message\":\"OAuth dosyası kaydedildi\"}");}
    // Açıklama: Google yetkilendirmesinde kullanılacak istemci yapılandırmasını okur.
    static Map<String,String> googleClient()throws Exception{if(!Files.exists(GOOGLE_CREDS))throw new IllegalStateException("Önce OAuth credentials.json yükleyin");String s=Files.readString(GOOGLE_CREDS,StandardCharsets.UTF_8);Map<String,String>m=new HashMap<>();m.put("client_id",jsonValue(s,"client_id"));m.put("client_secret",jsonValue(s,"client_secret"));m.put("auth_uri",Optional.ofNullable(jsonValue(s,"auth_uri")).orElse("https://accounts.google.com/o/oauth2/auth"));m.put("token_uri",Optional.ofNullable(jsonValue(s,"token_uri")).orElse("https://oauth2.googleapis.com/token"));return m;}
    // Açıklama: OAuth state ve PKCE değerlerini hazırlayıp tarayıcıyı Google yetkilendirmesine yönlendirir.
    static void googleConnect(HttpExchange x)throws Exception{Map<String,String>g=googleClient();Properties p=loadConfig();String state=randomToken(24),ver=randomToken(48),challenge=base64Url(MessageDigest.getInstance("SHA-256").digest(ver.getBytes(StandardCharsets.US_ASCII)));p.setProperty("oauth.state",state);p.setProperty("oauth.verifier",ver);saveConfig(p);String redirect="http://127.0.0.1:"+currentPort+"/api/google/callback";String scope="https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/userinfo.email openid";String u="https://accounts.google.com/o/oauth2/v2/auth?client_id="+enc(g.get("client_id"))+"&redirect_uri="+enc(redirect)+"&response_type=code&scope="+enc(scope)+"&access_type=offline&prompt=consent&state="+enc(state)+"&code_challenge="+enc(challenge)+"&code_challenge_method=S256";x.getResponseHeaders().set("Location",u);x.sendResponseHeaders(302,-1);x.close();}
    // Açıklama: OAuth dönüşünde state değerini doğrular; kodu erişim belirteçlerine çevirerek hesabı kaydeder.
    static void googleCallback(HttpExchange x)throws Exception{Map<String,String>q=queryMap(x.getRequestURI().getRawQuery());Properties p=loadConfig();if(!Objects.equals(q.get("state"),p.getProperty("oauth.state")))throw new SecurityException("OAuth state doğrulaması başarısız");String code=q.get("code");if(code==null)throw new IOException("Google yetkilendirme kodu gelmedi");Map<String,String>g=googleClient();String redirect="http://127.0.0.1:"+currentPort+"/api/google/callback";String body=form(Map.of("code",code,"client_id",g.get("client_id"),"client_secret",g.get("client_secret"),"redirect_uri",redirect,"grant_type","authorization_code","code_verifier",p.getProperty("oauth.verifier","")));HttpRequest req=HttpRequest.newBuilder(URI.create(g.get("token_uri"))).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();HttpResponse<String>r=HTTP.send(req,HttpResponse.BodyHandlers.ofString());if(r.statusCode()/100!=2)throw new IOException("Google token hatası: "+r.body());String access=jsonValue(r.body(),"access_token"),refresh=jsonValue(r.body(),"refresh_token");long exp=parseLong(Optional.ofNullable(jsonRaw(r.body(),"expires_in")).orElse("3600"));if(access==null)throw new IOException("Access token alınamadı");p.setProperty("google.access_token",access);if(refresh!=null)p.setProperty("google.refresh_token",refresh);p.setProperty("google.expires_at",String.valueOf(System.currentTimeMillis()+Math.max(60,exp-60)*1000));p.remove("oauth.state");p.remove("oauth.verifier");String account=googleAccount(access);p.setProperty("google.account",account);saveConfig(p);html(x,"<!doctype html><meta charset='utf-8'><style>body{font:16px Segoe UI;background:#f4f7fb;padding:40px}.c{max-width:600px;margin:auto;background:white;padding:28px;border-radius:14px}</style><div class='c'><h2>✓ Google hesabı bağlandı</h2><p><b>"+escHtml(account)+"</b> Gmail bildirimleri için yetkilendirildi.</p><button onclick='window.close()'>Pencereyi Kapat</button></div>");}
    // Açıklama: Erişim belirteciyle Google hesap bilgisini sorgular.
    static String googleAccount(String token)throws Exception{HttpRequest rq=HttpRequest.newBuilder(URI.create("https://www.googleapis.com/oauth2/v2/userinfo")).header("Authorization","Bearer "+token).GET().build();HttpResponse<String>r=HTTP.send(rq,HttpResponse.BodyHandlers.ofString());String e=jsonValue(r.body(),"email");return e==null?"Google hesabı":e;}
    static void googleDisconnect(HttpExchange x)throws Exception{Properties p=loadConfig();for(String k:List.of("google.access_token","google.refresh_token","google.expires_at","google.account","oauth.state","oauth.verifier"))p.remove(k);saveConfig(p);json(x,200,"{\"ok\":true}");}
    // Açıklama: Süresi dolmamış erişim belirtecini kullanır; gerekirse yenileme belirteciyle yenisini alır.
    static String googleAccessToken(Properties p)throws Exception{long exp=parseLong(p.getProperty("google.expires_at","0"));String a=p.getProperty("google.access_token","");if(!a.isBlank()&&System.currentTimeMillis()<exp)return a;String ref=p.getProperty("google.refresh_token","");if(ref.isBlank())throw new IllegalStateException("Google hesabı bağlı değil");Map<String,String>g=googleClient();String body=form(Map.of("client_id",g.get("client_id"),"client_secret",g.get("client_secret"),"refresh_token",ref,"grant_type","refresh_token"));HttpRequest req=HttpRequest.newBuilder(URI.create(g.get("token_uri"))).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();HttpResponse<String>r=HTTP.send(req,HttpResponse.BodyHandlers.ofString());if(r.statusCode()/100!=2)throw new IOException("Google token yenileme hatası: "+r.body());a=jsonValue(r.body(),"access_token");long secs=parseLong(Optional.ofNullable(jsonRaw(r.body(),"expires_in")).orElse("3600"));p.setProperty("google.access_token",a);p.setProperty("google.expires_at",String.valueOf(System.currentTimeMillis()+Math.max(60,secs-60)*1000));saveConfig(p);return a;}
    // Açıklama: Hazırlanan MIME e-postasını Gmail API üzerinden gönderir.
    static void sendGmail(Properties p,String subject,String body)throws Exception{String token=googleAccessToken(p),from=p.getProperty("google.account","");String rcpts=p.getProperty("email.recipients","");String mime="From: "+from+"\r\nTo: "+rcpts+"\r\nSubject: =?UTF-8?B?"+Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8))+"?=\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"+body;String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(mime.getBytes(StandardCharsets.UTF_8));HttpRequest req=HttpRequest.newBuilder(URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"raw\":"+js(raw)+"}")).build();HttpResponse<String>r=HTTP.send(req,HttpResponse.BodyHandlers.ofString());if(r.statusCode()/100!=2)throw new IOException("Gmail gönderim hatası: "+r.body());}

    static String thumbnailSettingsJson()throws Exception{Properties p=loadThumbConfig();StringBuilder s=new StringBuilder("{");boolean first=true;for(Camera c:cameras){String ip=c.ip,k=ipKey(ip);String mode=p.getProperty(k+".mode","none"),url=p.getProperty(k+".url",""),file=p.getProperty(k+".file","");if(mode.equals("none")&&url.isBlank()&&file.isBlank())continue;if(!first)s.append(',');first=false;s.append(js(ip)).append(":{\"ip\":").append(js(ip)).append(",\"mode\":").append(js(mode)).append(",\"url\":").append(js(url)).append(",\"local_file\":").append(js(file)).append('}');}return s.append('}').toString();}
    static void thumbnailSetting(HttpExchange x)throws Exception{String b=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);String ip=jsonValue(b,"ip"),mode=jsonValue(b,"mode"),url=jsonValue(b,"url");if(ip==null||mode==null)throw new IllegalArgumentException("IP/mod eksik");Properties p=loadThumbConfig();String k=ipKey(ip);p.setProperty(k+".mode",mode);if(url!=null)p.setProperty(k+".url",url);saveThumbConfig(p);json(x,200,"{\"ok\":true}");}
    static void thumbnailUpload(HttpExchange x)throws Exception{List<Part>parts=parseMultipart(x);String ip=parts.stream().filter(z->z.name.equals("ip")).map(z->new String(z.data,StandardCharsets.UTF_8).trim()).findFirst().orElse("");Part f=parts.stream().filter(z->!z.filename.isBlank()).findFirst().orElse(null);if(ip.isBlank()||f==null)throw new IOException("IP veya görsel bulunamadı");String ext=".jpg";int dot=f.filename.lastIndexOf('.');if(dot>=0&&dot>f.filename.length()-7)ext=f.filename.substring(dot).replaceAll("[^A-Za-z0-9.]","");String name=ip.replace('.','_')+"_"+System.currentTimeMillis()+ext;Files.write(THUMB_DIR.resolve(name),f.data);Properties p=loadThumbConfig();String k=ipKey(ip);p.setProperty(k+".mode","local");p.setProperty(k+".file",name);saveThumbConfig(p);json(x,200,"{\"ok\":true}");}
    static void thumbnailGet(HttpExchange x,String ip)throws Exception{Properties p=loadThumbConfig();String k=ipKey(ip),mode=p.getProperty(k+".mode","none");if(mode.equals("local")){Path f=THUMB_DIR.resolve(p.getProperty(k+".file",""));if(!Files.exists(f)){x.sendResponseHeaders(404,-1);return;}sendFile(x,f,contentType(f),f.getFileName().toString());return;}if(mode.equals("url")){String u=p.getProperty(k+".url","");if(u.isBlank()){x.sendResponseHeaders(404,-1);return;}HttpRequest rq=HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(8)).GET().build();HttpResponse<byte[]>r=HTTP.send(rq,HttpResponse.BodyHandlers.ofByteArray());if(r.statusCode()/100!=2){x.sendResponseHeaders(502,-1);return;}byte[]b=r.body();x.getResponseHeaders().set("Content-Type",r.headers().firstValue("Content-Type").orElse("image/jpeg"));noCache(x);x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();return;}x.sendResponseHeaders(404,-1);}

    // Açıklama: Güncel kamera listesinden Excel dışa aktarımı üretir; Excel ana veri deposu değildir.
    static synchronized void syncExcel(){try{List<String>h=List.of("Kamera","IP","MAC Adresi","Bölge","Sunucu","Sunucu IP","Aktif","Durum","Ping (ms)","Son Kontrol","Offline Süresi");List<List<String>>rows=new ArrayList<>();long now=System.currentTimeMillis();for(Camera c:cameras){long off=c.offlineSince>0?(now-c.offlineSince)/1000:0;rows.add(List.of(c.name,c.ip,c.mac,c.region,c.server,c.serverIp,c.enabled?"Evet":"Hayır",c.enabled?c.status:"PASİF",c.ping==null?"":c.ping.toString(),fmtTs(c.lastCheck),c.status.equals("OFFLINE")?formatDuration(off):"-"));}Xlsx.write(XLSX,"Kameralar",h,rows);lastExcelSync=System.currentTimeMillis();}catch(Exception e){logError("Excel senkronizasyonu",e);System.err.println("Excel senkronizasyonu: "+e);}}
    // Açıklama: Olay Excel dosyasını indirir ve geçici çıktıyı siler; bakım ile aynı anda dosya işlemi yapılmasını önler.
    static synchronized void sendEventExport(HttpExchange x,boolean history)throws Exception{
        Path file=history?buildEventsXlsx():buildLogXlsx();
        try{sendFile(x,file,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",file.getFileName().toString());}
        finally{Files.deleteIfExists(file);}
    }
    // Açıklama: Yalnızca bugünün olaylarını Excel dosyasına aktarır.
    static Path buildLogXlsx()throws Exception{return writeEventXlsx(readTsv(todayLog()),DATA.resolve("kamera_log_"+LocalDate.now()+".xlsx"));}
    // Açıklama: Saklama süresi içindeki olay geçmişini Excel dosyasına aktarır.
    static Path buildEventsXlsx()throws Exception{return writeEventXlsx(readEventHistory(),DATA.resolve("kamera_olaylari.xlsx"));}
    // Açıklama: Günlük log ve geçmiş çıktıları için ortak dokuz sütunlu Excel düzenini kullanır.
    static Path writeEventXlsx(List<String[]> records,Path file)throws Exception{
        List<List<String>> rows=new ArrayList<>();for(String[] row:records)rows.add(Arrays.asList(row));
        Xlsx.write(file,"Olaylar",List.of("Tarih Saat","Kamera","IP","Sunucu","Olay","Eski Durum","Yeni Durum","Süre (sn)","Detay"),rows);return file;
    }
    // Açıklama: Bugünkü olayların önizlemesini oluşturur.
    static String logPreviewJson(){return eventRowsJson(readTsv(todayLog()));}
    // Açıklama: Saklanan olay geçmişinin önizlemesini oluşturur.
    static String eventPreviewJson(){return eventRowsJson(readEventHistory());}
    // Açıklama: Toplam olay sayısını ve son 500 kaydı arayüz için JSON olarak hazırlar.
    static String eventRowsJson(List<String[]> rows){int st=Math.max(0,rows.size()-500);StringBuilder s=new StringBuilder("{\"total\":").append(rows.size()).append(",\"rows\":[");boolean first=true;for(int i=st;i<rows.size();i++){String[]a=rows.get(i);if(a.length<9)continue;if(!first)s.append(',');first=false;s.append("{\"ts\":").append(js(a[0])).append(",\"name\":").append(js(a[1])).append(",\"ip\":").append(js(a[2])).append(",\"server\":").append(js(a[3])).append(",\"event_type\":").append(js(a[4])).append(",\"old_status\":").append(js(a[5])).append(",\"new_status\":").append(js(a[6])).append(",\"duration_seconds\":").append(a[7].isBlank()?"0":a[7]).append(",\"details\":").append(js(a[8])).append('}');}return s.append("]}").toString();}

    static Properties loadConfig()throws IOException{Properties p=new Properties();if(Files.exists(CONFIG))try(Reader r=Files.newBufferedReader(CONFIG,StandardCharsets.UTF_8)){p.load(r);}return p;}
    static void ensureConfig()throws IOException{if(Files.exists(CONFIG))return;Properties p=new Properties();p.setProperty("email.enabled","true");p.setProperty("email.method","system");p.setProperty("email.recipients","");p.setProperty("offline.minutes","60");saveConfig(p);}
    static synchronized void saveConfig(Properties p)throws IOException{try(Writer w=Files.newBufferedWriter(CONFIG,StandardCharsets.UTF_8)){p.store(w,"IP Kamera Monitor "+VERSION);}}
    static Properties loadThumbConfig()throws IOException{Properties p=new Properties();if(Files.exists(THUMB_CFG))try(Reader r=Files.newBufferedReader(THUMB_CFG,StandardCharsets.UTF_8)){p.load(r);}return p;}
    static synchronized void saveThumbConfig(Properties p)throws IOException{try(Writer w=Files.newBufferedWriter(THUMB_CFG,StandardCharsets.UTF_8)){p.store(w,"Thumbnails");}}

    // Açıklama: Dosya yükleme isteğini sınır belirtecine göre bölerek form alanlarını ve dosya içeriğini çıkarır.
    static List<Part> parseMultipart(HttpExchange x)throws Exception{String ct=x.getRequestHeaders().getFirst("Content-Type");if(ct==null||!ct.contains("boundary="))throw new IOException("multipart/form-data bekleniyor");String boundary=ct.substring(ct.indexOf("boundary=")+9).trim();if(boundary.startsWith("\"")&&boundary.endsWith("\""))boundary=boundary.substring(1,boundary.length()-1);byte[]all=x.getRequestBody().readAllBytes();String raw=new String(all,StandardCharsets.ISO_8859_1),del="--"+boundary;List<Part>out=new ArrayList<>();int pos=0;while(true){int b=raw.indexOf(del,pos);if(b<0)break;int hstart=b+del.length();if(raw.startsWith("--",hstart))break;if(raw.startsWith("\r\n",hstart))hstart+=2;int hend=raw.indexOf("\r\n\r\n",hstart);if(hend<0)break;String hdr=raw.substring(hstart,hend);int ds=hend+4,de=raw.indexOf("\r\n"+del,ds);if(de<0)break;Part p=new Part();Matcher nm=Pattern.compile("name=\"([^\"]+)\"").matcher(hdr);if(nm.find())p.name=nm.group(1);Matcher fm=Pattern.compile("filename=\"([^\"]*)\"").matcher(hdr);if(fm.find())p.filename=fm.group(1);Matcher cm=Pattern.compile("(?i)Content-Type:\\s*([^\\r\\n]+)").matcher(hdr);if(cm.find())p.contentType=cm.group(1).trim();p.data=Arrays.copyOfRange(all,ds,de);out.add(p);pos=de;}return out;}

    static class Xlsx {
        static List<Camera> read(Path path)throws Exception{try(ZipFile z=new ZipFile(path.toFile())){List<String>shared=shared(z);ZipEntry sheet=z.getEntry("xl/worksheets/sheet1.xml");if(sheet==null)throw new IOException("Excel sheet1 bulunamadı");Document d=parse(z.getInputStream(sheet));NodeList rs=d.getElementsByTagNameNS("*","row");List<List<String>>table=new ArrayList<>();for(int i=0;i<rs.getLength();i++){Element row=(Element)rs.item(i);NodeList cs=row.getElementsByTagNameNS("*","c");Map<Integer,String>vals=new HashMap<>();int max=0;for(int j=0;j<cs.getLength();j++){Element c=(Element)cs.item(j);int col=colIndex(c.getAttribute("r"));String t=c.getAttribute("t"),v="";if("inlineStr".equals(t)){NodeList ts=c.getElementsByTagNameNS("*","t");if(ts.getLength()>0)v=ts.item(0).getTextContent();}else{NodeList vs=c.getElementsByTagNameNS("*","v");if(vs.getLength()>0){v=vs.item(0).getTextContent();if("s".equals(t)){int ix=Integer.parseInt(v);v=ix<shared.size()?shared.get(ix):"";}}}vals.put(col,v);max=Math.max(max,col);}List<String>rr=new ArrayList<>(Collections.nCopies(max+1,""));for(var e:vals.entrySet())rr.set(e.getKey(),e.getValue());table.add(rr);}if(table.isEmpty())return new ArrayList<>();Map<String,Integer>h=new HashMap<>();for(int i=0;i<table.get(0).size();i++)h.put(norm(table.get(0).get(i)),i);if(!h.containsKey("kamera")||!h.containsKey("ip")||!h.containsKey("bölge")||!h.containsKey("sunucu"))throw new IOException("Excel en az Kamera, IP, Bölge ve Sunucu sütunlarını içermelidir");List<Camera>out=new ArrayList<>();for(int r=1;r<table.size();r++){List<String>a=table.get(r);Camera c=new Camera();c.name=get(a,h,"kamera");c.ip=get(a,h,"ip");if(c.name.isBlank()||c.ip.isBlank())continue;c.mac=get(a,h,"mac adresi").toUpperCase(Locale.ROOT);c.region=get(a,h,"bölge");c.server=get(a,h,"sunucu");if(c.server.isBlank())c.server="Tanımsız Sunucu";c.serverIp=get(a,h,"sunucu ip");out.add(c);}return out;}}
        static void write(Path path,String sheetName,List<String>headers,List<List<String>>rows)throws Exception{Files.createDirectories(path.getParent());try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(path))){put(z,"[Content_Types].xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");put(z,"_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");put(z,"xl/workbook.xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\""+xml(sheetName)+"\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");put(z,"xl/_rels/workbook.xml.rels","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");StringBuilder sh=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetData>");List<List<String>>all=new ArrayList<>();all.add(headers);all.addAll(rows);for(int r=0;r<all.size();r++){sh.append("<row r=\"").append(r+1).append("\">");List<String>a=all.get(r);for(int c=0;c<a.size();c++){String ref=colName(c+1)+(r+1);sh.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(a.get(c))).append("</t></is></c>");}sh.append("</row>");}sh.append("</sheetData></worksheet>");put(z,"xl/worksheets/sheet1.xml",sh.toString());}}
        static void put(ZipOutputStream z,String name,String s)throws Exception{z.putNextEntry(new ZipEntry(name));z.write(s.getBytes(StandardCharsets.UTF_8));z.closeEntry();}
        static String norm(String s){return n(s).trim().toLowerCase(Locale.ROOT);}static String get(List<String>a,Map<String,Integer>h,String k){Integer i=h.get(k);return i==null||i>=a.size()?"":a.get(i).trim();}static int colIndex(String ref){int n=0;for(char ch:ref.toCharArray()){if(Character.isLetter(ch))n=n*26+(Character.toUpperCase(ch)-'A'+1);else break;}return Math.max(0,n-1);}static String colName(int n){StringBuilder s=new StringBuilder();while(n>0){n--;s.append((char)('A'+n%26));n/=26;}return s.reverse().toString();}static List<String>shared(ZipFile z)throws Exception{List<String>o=new ArrayList<>();ZipEntry e=z.getEntry("xl/sharedStrings.xml");if(e==null)return o;Document d=parse(z.getInputStream(e));NodeList sis=d.getElementsByTagNameNS("*","si");for(int i=0;i<sis.getLength();i++){Element si=(Element)sis.item(i);NodeList ts=si.getElementsByTagNameNS("*","t");StringBuilder s=new StringBuilder();for(int j=0;j<ts.getLength();j++)s.append(ts.item(j).getTextContent());o.add(s.toString());}return o;}static Document parse(InputStream in)throws Exception{DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setNamespaceAware(true);try(in){return f.newDocumentBuilder().parse(in);}}
    }

    static void html(HttpExchange x,String s)throws IOException{byte[]b=s.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");noCache(x);x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();}
    static void json(HttpExchange x,int code,String s)throws IOException{byte[]b=s.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");noCache(x);x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);x.close();}
    // Açıklama: Kamera, durum, görsel ve kimlik bilgilerini içerebilen yapılandırma yedeğini ZIP olarak indirir.
    static void backupDownload(HttpExchange x)throws Exception{
        if(!x.getRequestMethod().equalsIgnoreCase("GET")){json(x,405,"{}");return;}
        syncExcel(); persistState();
        Path tmp=Files.createTempFile(DATA,"ipkamera_yedek_",".zip");
        try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(tmp))){
            Properties mf=new Properties();
            mf.setProperty("app.version",VERSION); mf.setProperty("created.at",LocalDateTime.now().format(DT));
            mf.setProperty("contains.sensitive.credentials","true");
            ByteArrayOutputStream bo=new ByteArrayOutputStream(); mf.store(bo,"IP Kamera Monitor backup manifest");
            addZipBytes(z,"manifest.properties",bo.toByteArray());
            syncExcel(); addZipIfExists(z,XLSX,"kamera_listesi.xlsx");
            addZipIfExists(z,CAMERAS_CFG,"cameras.properties");
            addZipIfExists(z,CONFIG,"config.properties");
            addZipIfExists(z,SYSTEM_MAIL_KEY_FILE,"system_mail.key");
            addZipIfExists(z,STATE,"camera_state.properties");
            addZipIfExists(z,THUMB_CFG,"thumbnails.properties");
            addZipIfExists(z,GOOGLE_CREDS,"credentials.json");
            if(Files.isDirectory(THUMB_DIR)) try(var st=Files.list(THUMB_DIR)){for(Path f:(Iterable<Path>)st.filter(Files::isRegularFile)::iterator)addZipIfExists(z,f,"thumbnails/"+f.getFileName());}
            String note="Bu yedek kamera IP bilgileri, kamera görselleri ve e-posta/OAuth yapılandırması içerebilir. Dosyayı güvenli saklayın.\\r\\n";
            addZipBytes(z,"YEDEK_BILGI.txt",note.getBytes(StandardCharsets.UTF_8));
        }
        try{sendFile(x,tmp,"application/zip","IPKameraMonitor_Yedek_"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))+".zip");}finally{Files.deleteIfExists(tmp);}
    }

    static void addZipIfExists(ZipOutputStream z,Path f,String name)throws IOException{if(!Files.exists(f)||!Files.isRegularFile(f))return;z.putNextEntry(new ZipEntry(name.replace('\\','/')));Files.copy(f,z);z.closeEntry();}
    static void addZipBytes(ZipOutputStream z,String name,byte[]b)throws IOException{z.putNextEntry(new ZipEntry(name));z.write(b);z.closeEntry();}

    // Açıklama: İzin verilen yedek dosyalarını geçici klasörde açıp yükler; işlem sırasında izlemeyi durdurur.
    static void backupRestore(HttpExchange x)throws Exception{
        if(!x.getRequestMethod().equalsIgnoreCase("POST")){json(x,405,"{}");return;}
        List<Part> parts=parseMultipart(x); Part file=parts.stream().filter(p->!p.filename.isBlank()).findFirst().orElse(null);
        if(file==null)throw new IOException("Yedek ZIP dosyası bulunamadı");
        boolean wasRunning=monitoring.getAndSet(false);
        Path tmpDir=Files.createTempDirectory(DATA,"restore_");
        try{
            Set<String> allowed=Set.of("manifest.properties","kamera_listesi.xlsx","cameras.properties","config.properties","camera_state.properties","thumbnails.properties","credentials.json","YEDEK_BILGI.txt");
            try(ZipInputStream zin=new ZipInputStream(new ByteArrayInputStream(file.data))){
                ZipEntry e; int count=0;
                while((e=zin.getNextEntry())!=null){
                    String n=e.getName().replace('\\','/');
                    if(e.isDirectory()){zin.closeEntry();continue;}
                    if(n.contains("..")||n.startsWith("/")||n.startsWith("\\"))throw new IOException("Geçersiz yedek girdisi: "+n);
                    boolean ok=allowed.contains(n)||n.startsWith("thumbnails/");
                    if(!ok){zin.closeEntry();continue;}
                    Path out=tmpDir.resolve(n).normalize(); if(!out.startsWith(tmpDir))throw new IOException("Geçersiz yedek yolu");
                    Files.createDirectories(out.getParent()); Files.copy(zin,out,StandardCopyOption.REPLACE_EXISTING); count++; zin.closeEntry();
                    if(count>5000)throw new IOException("Yedek dosyasında çok fazla girdi var");
                }
            }
            Path newX=tmpDir.resolve("kamera_listesi.xlsx"); if(!Files.exists(newX))throw new IOException("Yedekte kamera_listesi.xlsx bulunamadı");
            Xlsx.read(newX);
            Files.copy(newX,XLSX,StandardCopyOption.REPLACE_EXISTING);
            copyRestoreFile(tmpDir.resolve("cameras.properties"),CAMERAS_CFG); copyRestoreFile(tmpDir.resolve("config.properties"),CONFIG); copyRestoreFile(tmpDir.resolve("camera_state.properties"),STATE);
            copyRestoreFile(tmpDir.resolve("thumbnails.properties"),THUMB_CFG); copyRestoreFile(tmpDir.resolve("credentials.json"),GOOGLE_CREDS);
            Path newThumbs=tmpDir.resolve("thumbnails");
            if(Files.isDirectory(THUMB_DIR))try(var st=Files.list(THUMB_DIR)){for(Path f:(Iterable<Path>)st::iterator)if(Files.isRegularFile(f))Files.deleteIfExists(f);}
            if(Files.isDirectory(newThumbs))try(var st=Files.list(newThumbs)){for(Path f:(Iterable<Path>)st.filter(Files::isRegularFile)::iterator)Files.copy(f,THUMB_DIR.resolve(f.getFileName()),StandardCopyOption.REPLACE_EXISTING);}
            if(Files.exists(CAMERAS_CFG))loadCameraStore();else importExcelToStore(true);
            if(wasRunning)monitoring.set(true);
            json(x,200,"{\"ok\":true,\"count\":"+cameras.size()+",\"message\":\"Yedek geri yüklendi\"}");
            scheduler.execute(Main::scanSafe);
        } finally {
            if(wasRunning)monitoring.set(true);
            deleteTree(tmpDir);
        }
    }
    static void copyRestoreFile(Path src,Path dst)throws IOException{if(Files.exists(src))Files.copy(src,dst,StandardCopyOption.REPLACE_EXISTING);else Files.deleteIfExists(dst);}
    static void deleteTree(Path p){try{if(!Files.exists(p))return;try(var w=Files.walk(p)){w.sorted(Comparator.reverseOrder()).forEach(q->{try{Files.deleteIfExists(q);}catch(Exception ignored){}});}}catch(Throwable ignored){}}

    // Açıklama: Dosya içeriğini tarayıcıya indirme adı ve içerik türüyle gönderir.
    static void sendFile(HttpExchange x,Path f,String ct,String name)throws IOException{byte[]b=Files.readAllBytes(f);x.getResponseHeaders().set("Content-Type",ct);x.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+name.replace("\"","")+"\"");noCache(x);x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();}
    // Açıklama: Dinamik API ve dosya yanıtlarının tarayıcı önbelleğinden eski gelmesini önler.
    static void noCache(HttpExchange x){x.getResponseHeaders().set("Cache-Control","no-store, no-cache, must-revalidate, max-age=0");x.getResponseHeaders().set("Pragma","no-cache");x.getResponseHeaders().set("Expires","0");}
    static String contentType(Path f){try{return Optional.ofNullable(Files.probeContentType(f)).orElse("application/octet-stream");}catch(Exception e){return "application/octet-stream";}}
    // Açıklama: Metni JSON dizgesinde güvenli kullanılacak biçimde kaçış karakterleriyle hazırlar.
    static String js(String s){if(s==null)return "null";StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}}return b.append('"').toString();}
    static String xml(String s){return n(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    static String escHtml(String s){return xml(s);}
    static String n(String s){return s==null?"":s;}
    static long parseLong(String s){try{return Long.parseLong(s.trim());}catch(Exception e){return 0;}}static Long parseLongObj(String s){try{return Long.valueOf(s.trim());}catch(Exception e){return null;}}
    static boolean bool(Properties p,String k,boolean d){return Boolean.parseBoolean(p.getProperty(k,String.valueOf(d)));}
    static String fmtTs(long ms){return ms<=0?"":LocalDateTime.ofInstant(Instant.ofEpochMilli(ms),ZoneId.systemDefault()).format(DT);}static String nowIso(){return LocalDateTime.now().format(ISO);}static String formatDuration(long s){long h=s/3600,m=(s%3600)/60,ss=s%60;return h>0?h+" sa "+m+" dk "+ss+" sn":m>0?m+" dk "+ss+" sn":ss+" sn";}
    static String enc(String s){return URLEncoder.encode(n(s),StandardCharsets.UTF_8);}static String form(Map<String,String>m){StringJoiner j=new StringJoiner("&");m.forEach((k,v)->j.add(enc(k)+"="+enc(v)));return j.toString();}static Map<String,String>queryMap(String q){Map<String,String>m=new HashMap<>();if(q==null)return m;for(String p:q.split("&")){String[]a=p.split("=",2);m.put(URLDecoder.decode(a[0],StandardCharsets.UTF_8),a.length>1?URLDecoder.decode(a[1],StandardCharsets.UTF_8):"");}return m;}

    static String ipKey(String ip){return Base64.getUrlEncoder().withoutPadding().encodeToString(n(ip).getBytes(StandardCharsets.UTF_8));}
    static String decodeIpKey(String k){try{return new String(Base64.getUrlDecoder().decode(k),StandardCharsets.UTF_8);}catch(Exception e){return "";}}
    static String randomToken(int bytes){byte[]b=new byte[bytes];new SecureRandom().nextBytes(b);return base64Url(b);}static String base64Url(byte[]b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    // Açıklama: Basit JSON metninden istenen metin alanını çıkarır; genel amaçlı JSON ayrıştırıcısı değildir.
    static String jsonValue(String s,String key){Matcher m=Pattern.compile("\\\""+Pattern.quote(key)+"\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(s);if(!m.find())return null;return m.group(1).replace("\\\"","\"").replace("\\\\","\\").replace("\\n","\n").replace("\\r","\r");}
    // Açıklama: Basit JSON içindeki sayı veya mantıksal değer gibi alanları metin olarak çıkarır.
    static String jsonRaw(String s,String key){Matcher m=Pattern.compile("\\\""+Pattern.quote(key)+"\\\"\\s*:\\s*([^,}]+)").matcher(s);return m.find()?m.group(1).trim().replace("\"",""):null;}
    static void setIfJson(Properties p,String body,String jsonKey,String propKey){String v=jsonValue(body,jsonKey);if(v==null){String r=jsonRaw(body,jsonKey);if(r!=null)v=r;}if(v!=null)p.setProperty(propKey,v);}
    // Açıklama: İşletim sisteminin varsayılan tarayıcısında yerel uygulama adresini açar.
    static void browseNow(int port){try{URI u=URI.create("http://127.0.0.1:"+port);if(Desktop.isDesktopSupported())Desktop.getDesktop().browse(u);}catch(Throwable ignored){}}
    // Açıklama: Sunucunun hazır olması için tarayıcı açılışını kısa bir gecikmeyle planlar.
    static void openBrowser(int port){scheduler.schedule(()->browseNow(port),800,TimeUnit.MILLISECONDS);}
}
