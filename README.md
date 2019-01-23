**自动化阿里支付，用selenium模拟浏览器自动支付订单**

运行前你需要安装:
- java1.8
- maven
- 最新谷歌浏览器(Chrome v70-72)
- 修改PTest.java第六行，填写你的支付宝账号、登陆密码、支付密码
- 修改PTest.java第七行，填写商家的支付form表单

在以下平台测试过:
- win10 64位
- ubuntu16.04 64位
- centos7 64位

使用:
```
cd alipay-payment-automation
mvn test
```
