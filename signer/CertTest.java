import java.io.*; import java.nio.file.*;
import com.android.apksig.internal.util.X509CertificateUtils;
public class CertTest {
  public static void main(String[] a) throws Exception {
    byte[] cert = Files.readAllBytes(Paths.get("/tmp/embedded-cert.der"));
    try {
      var c = X509CertificateUtils.generateCertificate(cert);
      System.out.println("APKSIG CERT OK: " + c.getSubjectX500Principal());
    } catch (Exception e) {
      System.out.println("APKSIG CERT FAIL: " + e);
      e.printStackTrace();
    }
  }
}
