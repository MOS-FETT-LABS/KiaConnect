import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * K1 experiment: does a plain JSSE TLS stack (like the app's Conscrypt) pass
 * Kia's Cloudflare gate with the reference header set (including `date`)?
 * Run on any JVM: javac KiaTlsProbe.java && java KiaTlsProbe
 */
public class KiaTlsProbe {
    public static void main(String[] args) throws Exception {
        String host = "api.owners.kia.com";
        String path = "/apigw/v1/prof/authUser";
        String deviceId = "550E8400-E29B-41D4-A716-446655440000";
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        }}, new java.security.SecureRandom());
        SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket(host, 443);
        socket.setEnabledProtocols(new String[]{"TLSv1.2"});
        try {
            socket.setEnabledCipherSuites(new String[]{
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
                "TLS_RSA_WITH_AES_128_CBC_SHA",
                "TLS_RSA_WITH_AES_256_CBC_SHA",
                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
            });
        } catch (IllegalArgumentException e) {
            System.out.println("note: some suites unsupported: " + e.getMessage());
        }
        socket.startHandshake();
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        String date = df.format(new Date());
        String body = "{\"deviceKey\":\"" + deviceId + "\",\"deviceType\":2,\"userCredential\":{\"userId\":\"x@y.z\",\"password\":\"x\"},\"tncFlag\":1}";
        StringBuilder req = new StringBuilder();
        req.append("POST ").append(path).append(" HTTP/1.1\r\n");
        req.append("host: ").append(host).append("\r\n");
        req.append("content-type: application/json;charset=utf-8\r\n");
        req.append("accept: application/json\r\n");
        req.append("accept-language: en-US,en;q=0.9\r\n");
        req.append("apptype: L\r\n");
        req.append("appversion: 7.22.0\r\n");
        req.append("clientid: SPACL716-APL\r\n");
        req.append("clientuuid: 550e8400-e29b-41d4-a716-446655440000\r\n");
        req.append("from: SPA\r\n");
        req.append("language: 0\r\n");
        req.append("offset: 0\r\n");
        req.append("ostype: iOS\r\n");
        req.append("osversion: 15.8.5\r\n");
        req.append("phonebrand: iPhone\r\n");
        req.append("secretkey: sydnat-9kykci-Kuhtep-h5nK\r\n");
        req.append("to: APIGW\r\n");
        req.append("tokentype: A\r\n");
        req.append("user-agent: KIAPrimo_iOS/37 CFNetwork/1335.0.3.4 Darwin/21.6.0\r\n");
        req.append("date: ").append(date).append("\r\n");
        req.append("deviceid: ").append(deviceId).append("\r\n");
        req.append("content-length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
        req.append("connection: close\r\n");
        req.append("\r\n").append(body);
        OutputStream out = socket.getOutputStream();
        out.write(req.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        socket.close();
        String response = buf.toString("ISO-8859-1");
        int head = response.indexOf("\r\n\r\n");
        String headPart = head >= 0 ? response.substring(0, head) : response;
        String bodyPart = head >= 0 ? response.substring(head + 4) : "";
        System.out.println("STATUS: " + headPart.split("\r\n")[0]);
        boolean chunked = headPart.toLowerCase(Locale.US).contains("transfer-encoding: chunked");
        String shown = bodyPart;
        if (chunked) {
            StringBuilder sb = new StringBuilder();
            InputStream bin = new ByteArrayInputStream(bodyPart.getBytes(StandardCharsets.ISO_8859_1));
            String sizeLine = readLine(bin);
            while (sizeLine != null && !sizeLine.trim().isEmpty()) {
                int size = Integer.parseInt(sizeLine.trim().split(";")[0], 16);
                byte[] data = new byte[size];
                int read = 0;
                while (read < size) read += bin.read(data, read, size - read);
                sb.append(new String(data, StandardCharsets.UTF_8));
                readLine(bin);
                sizeLine = readLine(bin);
            }
            shown = sb.toString();
        }
        System.out.println("BODY: " + shown.substring(0, Math.min(300, shown.length())));
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') return sb.toString().replace("\r", "");
            sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
