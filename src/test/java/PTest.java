import org.junit.jupiter.api.Test;

public class PTest {
    @Test
    public void test(){
        RobotPay bot = new RobotPay("","","");
        bot.submitPayTask("1","");
        while(bot.getPayResult("1") == null){
            continue;
        }
    }
}
