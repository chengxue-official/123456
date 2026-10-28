import okhttp3.ConnectionSpec;
public class SpecProbe {
  public static void main(String[] a) {
    ConnectionSpec[] all = {ConnectionSpec.MODERN_TLS, ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.COMPATIBLE_TLS};
    String[] names = {"MODERN_TLS", "RESTRICTED_TLS", "COMPATIBLE_TLS"};
    for (int i = 0; i < all.length; i++) {
      ConnectionSpec cs = all[i];
      if (!cs.isTls()) { System.out.println(names[i] + ": non-TLS"); continue; }
      StringBuilder sb = new StringBuilder(names[i] + ": tls=[");
      for (int j = 0; j < cs.tlsVersions().size(); j++) sb.append(j > 0 ? "," : "").append(cs.tlsVersions().get(j));
      sb.append("] ciphers=").append(cs.cipherSuites().size());
      System.out.println(sb);
    }
  }
}
