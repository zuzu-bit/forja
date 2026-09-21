import com.forja.app.feature.research.AudioReadyPolicy;
public class AudioReadyPolicyTest {
    private static int count;
    private static void check(boolean condition){count++;if(!condition)throw new AssertionError("Readiness check "+count);}
    public static void main(String[] args){
        check(AudioReadyPolicy.authorized("a","a","grant","grant",3,3,true,true));
        check(!AudioReadyPolicy.authorized(null,"a","grant","grant",3,3,true,true));
        check(!AudioReadyPolicy.authorized("a",null,"grant","grant",3,3,true,true));
        check(!AudioReadyPolicy.authorized("a","b","grant","grant",3,3,true,true));
        check(!AudioReadyPolicy.authorized("a","a",null,"grant",3,3,true,true));
        check(!AudioReadyPolicy.authorized("a","a","grant",null,3,3,true,true));
        check(!AudioReadyPolicy.authorized("a","a","","",3,3,true,true));
        check(!AudioReadyPolicy.authorized("a","a","old","new",3,3,true,true));
        check(!AudioReadyPolicy.authorized("a","a","grant","grant",3,4,true,true));
        check(!AudioReadyPolicy.authorized("a","a","grant","grant",3,3,false,true));
        check(!AudioReadyPolicy.authorized("a","a","grant","grant",3,3,true,false));
        check(AudioReadyPolicy.sameSession("session","session"));
        check(!AudioReadyPolicy.sameSession(null,"session"));
        check(!AudioReadyPolicy.sameSession("session",null));
        check(!AudioReadyPolicy.sameSession("",""));
        check(!AudioReadyPolicy.sameSession("old","new"));
        for(long value:new long[]{Long.MIN_VALUE,-1,0,4999,3600001,Long.MAX_VALUE})check(!AudioReadyPolicy.durationAllowed(value));
        for(long value:new long[]{5000,60000,900000,3600000})check(AudioReadyPolicy.durationAllowed(value));
        System.out.println(count+" readiness and bounded-duration checks passed");
    }
}
