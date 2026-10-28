import okhttp3.*;
public class Limit2 {
  static final String URL="https://open.feishu.cn/open-apis/bot/v2/hook/7a8c2ca0-be89-418e-9407-0548ca423792";
  public static void main(String[] a) throws Exception {
    int[] sizes={20000,24000,28000,30000,32000,40000,50000};
    for(int n:sizes){
      StringBuilder sb=new StringBuilder();
      while(sb.length()<n) sb.append("16:11:0").append(sb.length()%10).append(".000 [D] NetClient: 搜索关键词=闪婚 返回 22 条\n");
      String body=sb.substring(0,n);
      String resp=send(body+"\n[日志]");
      boolean ok=resp.contains("\"code\":0");
      System.out.println("chars="+n+" bytes="+body.getBytes("UTF-8").length+" -> "+(ok?"OK":"REJECT")+"  "+(resp.length()>120?resp.substring(0,120):resp));
    }
  }
  static String send(String c) throws Exception {
    String p="{\"msg_type\":\"interactive\",\"card\":{\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"[日志] 边界\"},\"template\":\"blue\"},\"elements\":[{\"tag\":\"markdown\",\"content\":\""+esc(c)+"\"}]}}";
    OkHttpClient cl=new OkHttpClient();
    Response r=cl.newCall(new Request.Builder().url(URL).header("Content-Type","application/json; charset=utf-8").post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"),p)).build()).execute();
    try { return r.code()+" "+(r.body()==null?"":r.body().string()); } finally { r.close(); }
  }
  static String esc(String s){StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);
    switch(c){case '"':b.append("\\\"");break;case '\':b.append("\\\\");break;case '\n':b.append("\n");break;
    case '\r':b.append("\r");break;case '\t':b.append("\t");break;default:if(c<' ')b.append(String.format("\u%04x",(int)c));else b.append(c);}}return b.toString();}
}
