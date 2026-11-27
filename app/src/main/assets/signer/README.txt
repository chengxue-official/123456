签名后备材料存放处（平时为空，走自研 PlaybackSignerV5）

换签名时间线：
  1) 只是版本号/种子/请求头变了 → 改 sign/ * .java 里的常量，无需这里的材料
  2) 算法变了但仍在官方 dex 里 → 把官方 classes.dex 放这里，命名 official.dex，
     并建 signer.json：
        { "class": "com.xxx.YourSigner", "method": "sign", "dex": "official.dex" }
     目标方法需为：
        static Map<String,String> sign(String url, Map<String,String> profile, byte[] body)
     或返回 { "url": "...", "headers": { ... } }
  3) 算法下沉到 native → 把官方 .so 放进 app/src/main/jniLibs/<abi>/（运行期不能从
     assets 加载 .so），上面的 dex 胶水层照旧调用；本目录仍放它的 Java 类所在的 dex。

失败会自动回退，不会因为材料坏掉导致播放全挂。
