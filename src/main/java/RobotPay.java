import org.apache.commons.lang3.SystemUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.Timer;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public class RobotPay {
    private WebDriver driver = null;
    private Object mutex = new Object();
    private Queue<OrderPayTuple> payqueue = new ConcurrentLinkedQueue();
    private String account = null;
    private String paypassword = null;
    private String loginpassword = null;
    private Timer loginjob;
    private Timer payjob;
    private Timer F5Job;
    private ConcurrentHashMap<String, PayResult> payresult = new ConcurrentHashMap<String, PayResult>();
    private String cancelorderid = null;
    ChromeOptions options = null;
    //ExecutorService executor = Executors.newSingleThreadExecutor();

    public RobotPay(String account, String loginpassword, String paypassword) {
        System.out.println("构造方法 开始");
        try {
            options = new ChromeOptions();
            HashMap<String, Object> chromePrefs = new HashMap<>();
            chromePrefs.put("download.default_directory", new File(RobotPay.class.getClassLoader().getResource("alipaycaptchatmp/").getFile()).getAbsolutePath());
            chromePrefs.put("download.prompt_for_download", false);
            chromePrefs.put("download.directory_upgrade", true);
            chromePrefs.put("safebrowsing.enabled", true);
            chromePrefs.put("profile.managed_default_content_settings.images", 2);//禁图片
            options.setExperimentalOption("prefs", chromePrefs);

            if (SystemUtils.IS_OS_LINUX) {
                System.setProperty("webdriver.chrome.driver", new File(RobotPay.class.getClassLoader().getResource("seleniumdriver/" + "chromedriver245").getFile()).getAbsolutePath());
                options.addArguments("--headless");
                options.addArguments("--disable-dev-shm-usage"); // overcome limited resource problems
                options.addArguments("--no-sandbox"); // Bypass OS security model
            } else if (SystemUtils.IS_OS_WINDOWS) {
                System.setProperty("webdriver.chrome.driver", new File(RobotPay.class.getClassLoader().getResource("seleniumdriver/" + "chromedriver245.exe").getFile()).getAbsolutePath());
            } else {
                return;
            }

            this.account = account;
            this.loginpassword = loginpassword;
            this.paypassword = paypassword;
            //每隔20秒检查浏览器已打开且账号已登陆
            loginjob = ensureOpenAndLoginTimer(1000);
            //每隔0.5秒支付一页
            payjob = payTimer(500);
            //每隔180秒刷新登录页
            F5Job = refreshTimer(240000);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        System.out.println("构造方法 结束");
    }

    //打开浏览器并登陆支付宝
    private Timer ensureOpenAndLoginTimer(long millis) {
        Timer myTimer = new Timer();
        myTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (mutex) {
                    try {
                        eusureOpen();
                        ensureLogin();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }, 0, millis);
        return myTimer;
    }

    //打开浏览器
    public void eusureOpen() {
        synchronized (mutex) {
            try {
                driver.getWindowHandles();
            } catch (Exception e) {
                System.out.println("浏览器未打开 开始打开浏览器");
                driver = new ChromeDriver(options);
                System.out.println("浏览器打开成功");
            }
        }
    }

    //登陆支付宝
    public void ensureLogin() throws InterruptedException {
        synchronized (mutex) {
            try {
                driver.switchTo().window(nthTab(driver.getWindowHandles(), 0));
                boolean logged = islogged();
                if(logged){
                    return;
                }
                System.out.println("未登录 开始登陆");
                driver.switchTo().window(nthTab(driver.getWindowHandles(), 0));
                driver.get("https://authsu18.alipay.com/login/index.htm");
                //登陆逻辑
                driver.findElement(By.xpath("//*[contains(text(), '账密登录')]")).click();
                for (int i = 0; i < 100; i++) {//循环试
                    sendk(driver.findElement(By.id("J-input-user")), this.account, 100);
                    sendk(driver.findElement(By.id("password_rsainput")), this.loginpassword, 100);
                    if (driver.findElement(By.id("J-input-checkcode")).isDisplayed()) {
                        if (driver.findElements(By.id("mydownload")).size() == 0) {
                            String script = "var mydownload = document.createElement('a');" +
                                    "mydownload.id = \"mydownload\";" +
                                    "var mydownloadtext = document.createTextNode(\"asdsafassa\");\n" +
                                    "mydownload.appendChild(mydownloadtext);" +
                                    "document.body.appendChild(mydownload);";
                            ((ChromeDriver) driver).executeScript(script);
                        }
                        String imagename = UUID.randomUUID().toString() + ".png";
                        String script = "document.getElementById(\"mydownload\").href=\"" + driver.findElement(By.id("J-checkcode-img")).getAttribute("src") + "\";" +
                                "document.getElementById(\"mydownload\").download=\"" + imagename + "\";";
                        ((ChromeDriver) driver).executeScript(script);
                        driver.findElement(By.id("mydownload")).click();
                        Thread.currentThread().sleep(1000);
                        String predict = AlipayCaptchaDetector.predict(Paths.get(new File(RobotPay.class.getClassLoader().getResource("alipaycaptchatmp/").getFile()).getAbsolutePath(), imagename).toString());
                        if (predict == null || predict.length() != 4) {
                            continue;
                        }
                        sendk(driver.findElement(By.id("J-input-checkcode")), predict, 100);
                    }
                    driver.findElement(By.xpath("//input[@value=\"登 录\"]")).click();//click会等待页面加载完
                    logged = islogged();
                    if (logged) {
                        System.out.println("登陆成功");
                        break;
                    } else {
                        continue;//其实不需要判断，最后行continue就行，若已登陆第一行sendk会报错
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void timedExecute(Callable<? extends Object> callee, long seconds) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.invokeAll(Arrays.asList(callee), seconds, TimeUnit.SECONDS);
            //executor.submit(callee).get(seconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean islogged() {
        /*//个人首页url
        if(driver.getCurrentUrl()==null || driver.getCurrentUrl().contains("https://authsu18.alipay.com/login/index.htm") || driver.getCurrentUrl().contains("data:,")){
            return false;
        }else{
            return true;
        }*/
        if (driver.getCurrentUrl() != null &&
                (driver.getCurrentUrl().contains("https://my.alipay.com/portal/i.htm") || driver.getCurrentUrl().contains("https://mrchportalweb.alipay.com/user/ihome.htm"))) {
            return true;
        } else {
            return false;
        }
    }

    private Timer payTimer(long millis) {
        Timer myTimer = new Timer();
        myTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (mutex) {
                    OrderPayTuple tuple = null;
                    if ((tuple = payqueue.poll()) == null) {
                        return;
                    }
                    System.out.println("支付 开始");
                    try {
                        //关掉其他tab再说
                        closeOther();
                        driver.switchTo().window(nthTab(driver.getWindowHandles(), 0));
                        ((ChromeDriver) driver).executeScript("window.open()");
                        driver.switchTo().window(nthTab(driver.getWindowHandles(), 1));
                        driver.get("data:text/html;charset=utf-8," + "\n" + tuple.payForm);//get会等待资源完全加载完成
                        if (driver.getPageSource() != null && (driver.getPageSource().contains("交易已经关闭") || driver.getPageSource().contains("支付存在钓鱼风险"))) {
                            //明确错误,赋值支付状态为UNPAID
                            payresult.put(tuple.orderId, new PayResult(PayStatus.Fail, "支付页面提示：交易已经关闭或支付存在钓鱼风险"));
                            return;
                        }
                        if (driver.getPageSource() != null && driver.getPageSource().contains("交易已经支付")) {
                            payresult.put(tuple.orderId, new PayResult(PayStatus.PAID, "支付页面提示：交易已经支付"));
                            return;
                        }
                        //朝阳公园是这样的
                        List<WebElement> _s = driver.findElements(By.id("J_changePayStyle"));
                        if(_s!=null && _s.size()>0){
                            _s.get(0).click();
                        }
                        if(driver.getPageSource().contains("打开手机支付宝")&&driver.getPageSource().contains("扫一扫继续付款")){
                            driver.switchTo().window(nthTab(driver.getWindowHandles(), 0));
                            driver.navigate().refresh();
                            return ;
                        }
                        //输入密码
                        sendk(driver.findElement(By.xpath("//input[@name=\"payPassword_rsainput\"]")), paypassword, 100);
                        //点击确认支付按钮
                        if(tuple.orderId.equals(cancelorderid)){
                            return;
                        }
                        driver.findElement(By.xpath("//input[@id=\"J_authSubmit\"]")).click();
                        //等待出现结果
                        boolean ispaid = (new WebDriverWait(driver, 5)).until(new ExpectedCondition<Boolean>() {
                            @Override
                            public Boolean apply(WebDriver webDriver) {
                                if (driver.getPageSource() != null && driver.getPageSource().contains("您已成功付款")) {
                                    return true;
                                }
                                return null;
                            }
                        });
                        if (ispaid) {
                            payresult.put(tuple.orderId, new PayResult(PayStatus.PAID, "支付页面提示：您已成功付款"));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        ((ChromeDriver) driver).executeScript("window.close()");
                    }
                    System.out.println("支付 结束");
                }
            }
        }, 0, millis);
        return myTimer;
    }

    private void closeOther() {
        synchronized (mutex) {
            Set<String> hds = driver.getWindowHandles();
            if (hds.size() <= 1) {
                return;
            } else {
                int index = 0;
                for (String s : hds) {
                    if (index == 0) {
                        //第一个为登录页，不关闭
                        index++;
                        continue;
                    }
                    driver.switchTo().window(s);
                    ((ChromeDriver) driver).executeScript("window.close()");
                    index++;
                }

            }
        }
    }

    private void sendk(WebElement el, String s, long millis) throws InterruptedException {
        synchronized (mutex) {
            el.clear();
            if (millis <= 0) {
                el.sendKeys(s);
            } else {
                for (int i = 0; i < s.length(); i++) {
                    Thread.currentThread().sleep(millis);
                    String c = s.substring(i, i + 1);
                    el.sendKeys(c);
                }
            }
        }
    }

    private Timer refreshTimer(long millis) {
        Timer myTimer = new Timer();
        myTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (mutex) {
                    System.out.println("刷新 开始");
                    driver.switchTo().window(nthTab(driver.getWindowHandles(), 0));
                    /*if (driver.getCurrentUrl() != null && driver.getCurrentUrl().contains("https://authsu18.alipay.com/login/index.htm")) {
                        //不刷新登录页，值刷新个人主页
                        return;
                    }*/
                    try {
                        driver.switchTo().window(nthTab(driver.getWindowHandles(), 0));
                        driver.navigate().refresh();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    System.out.println("刷新 结束");
                }
            }
        }, millis, millis);
        return myTimer;
    }

    private final <T> T nthTab(Set<T> data, int n) {
        synchronized (mutex) {
            int index = 0;
            for (T element : data) {
                if (index == n) {
                    return element;
                }
                index++;
            }
            return null;
        }
    }

    /**
     * 手工取消支付task
     *
     * @param orderid
     */
    public void cancelPay(String orderid) {
        payqueue.remove(new OrderPayTuple(orderid, ""));
        cancelorderid = orderid;
    }

    /**
     * 1.提交支付
     *
     * @param orderid
     * @param payform
     */
    public void submitPayTask(String orderid, String payform) {
        OrderPayTuple op = new OrderPayTuple(orderid, payform);
        if (payresult.get(orderid) == null && !payqueue.contains(op)) {
            System.out.println("提交成功");
            payqueue.add(op);
        }
    }

    public PayResult getPayResult(String orderid) {
        return payresult.get(orderid);
    }

    @Override
    public void finalize() {
        try {
            loginjob.cancel();
            payjob.cancel();
            F5Job.cancel();
            driver.close();
            driver.quit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void shutdown() {
        finalize();
    }

    public void main(String[] args) throws InterruptedException {
        RobotPay robot = new RobotPay("", "", "");
        robot.submitPayTask("1","");
        while(payresult.get("1") == null){
            continue;
        }
    }

    public static enum PayStatus {
        PAID,
        Fail
    }

    public class PayResult {
        public PayStatus status;
        public String msg;

        public PayResult(PayStatus status, String msg) {
            this.status = status;
            this.msg = msg;
        }
    }

    class OrderPayTuple {
        public String orderId;
        public String payForm;

        public OrderPayTuple(String orderid, String payform) {
            this.orderId = orderid;
            this.payForm = payform;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (this.getClass().equals(obj.getClass())) {
                return ((OrderPayTuple) obj).orderId.equals(orderId);
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }
}




