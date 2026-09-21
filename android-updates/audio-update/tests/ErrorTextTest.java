import com.forja.app.feature.research.ErrorText;
public class ErrorTextTest {
    private static int checks;
    private static void check(boolean ok) { if(!ok)throw new AssertionError();checks++; }
    public static void main(String[] args) {
        check(ErrorText.status("HTTP 423: intake_paused")==423);
        check(ErrorText.status("Server 401: invalid token")==401);
        check(ErrorText.status("status: 503")==503);
        check(ErrorText.status("file 401 photos")==0);
        check(ErrorText.status(null)==0);
        check(ErrorText.connection(423,"IOException").contains("oprită"));
        check(ErrorText.connection(401,"IOException").contains("autentificarea"));
        check(ErrorText.connection(404,"IOException").contains("actualizarea"));
        check(ErrorText.connection(0,"UnknownHostException").contains("DNS"));
        check(ErrorText.connection(0,"SSLHandshakeException").contains("securizată"));
        check(ErrorText.connection(0,"SocketTimeoutException").contains("expirat"));
        String sensitive="https://server.invalid/?token=private";
        check(!ErrorText.connection(0,sensitive).contains(sensitive));
        check(ErrorText.connection(500,"Exception").equals(ErrorText.connection(503,"Exception")));
        for(int code:new int[]{200,201,204}){ErrorText.requireHttpSuccess(code);check(true);}
        for(int code:new int[]{401,403,404,409,423,429,500,503}){
            try{ErrorText.requireHttpSuccess(code);throw new AssertionError();}
            catch(IllegalStateException expected){check(ErrorText.status(expected.getMessage())==code);}
        }
        System.out.println(checks+" diagnostic classification checks passed");
    }
}
