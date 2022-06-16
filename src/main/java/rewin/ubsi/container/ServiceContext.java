package rewin.ubsi.container;

import io.netty.channel.Channel;
import rewin.ubsi.annotation.USEntry;
import rewin.ubsi.annotation.USParam;
import rewin.ubsi.common.IOData;
import rewin.ubsi.common.JedisUtil;
import rewin.ubsi.common.LogUtil;
import rewin.ubsi.common.Util;
import rewin.ubsi.consumer.*;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * 服务请求的上下文
 */
public class ServiceContext {

    Channel     Sock;
    InetAddress Remote;
    String      ReqID;          // 请求的ID，不能为null和""
    Map<String,Object> Header;  // 请求头
    String      Service;        // 服务名字，不能为null，""表示访问"容器"
    String      Entry;          // 方法名字，不能为null和""
    Object[]    Param;          // 参数，第1个必须为String，表示方法名字，不能为null
    byte        Flag;           // 标志，详见Context.FLAG_XXX

    boolean     Forwarded;      // 是否已转发
    String      Filter;         // 过滤器的类名字，非null表示正在执行过滤器
    boolean     Result = false;                 // 是否已经有结果
    int         ResultCode = ErrorCode.OK;      // 结果代码
    Object      ResultData = null;              // 结果数据 或 异常

    /** 根据名字构造对象 */
    public ServiceContext(String name) {
        Service = name;
    }
    /** 根据UBSI请求数据构造对象 */
    ServiceContext(Channel ch, Object data) throws Exception {
        Sock = ch;
        Remote = ((InetSocketAddress)ch.remoteAddress()).getAddress();
        Object[] req = (Object[]) data;
        ReqID = (String) req[0];
        Header = (Map)req[1];
        Service = (String) req[2];
        Param = (Object[]) req[3];
        Flag = (Byte) req[4];
        Entry = (String) Param[0];
        Param[0] = this;
        if ( ReqID == null || ReqID.length() == 0 || Service == null || Entry == null || Entry.length() == 0 )
            throw new Exception("null or empty field");

        // 从header的request_params中获取请求参数，since 2.0.1
        if ( Param.length == 1 && Header != null ) {
            Map<String, Object> params = (Map)Header.get(Context.REQUEST_PARAMS);
            if ( params != null ) {
                Service srv = Bootstrap.ServiceMap.get(Service);
                if ( srv != null ) {
                    Service.Entry entry = srv.EntryMap.get(Entry);
                    if ( entry != null ) {
                        USParam[] usParams = entry.JAnnotation.params();
                        Object[] param = new Object[usParams.length + 1];
                        for ( int i = 0; i < usParams.length; i ++ )
                            param[i+1] = params.get(usParams[i].name());
                        param[0] = this;
                        Param = param;
                    }
                }
            }
        }
    }

    /* 返回UBSI请求结果 */
    void response() {
        if ( (Flag & Context.FLAG_DISCARD) != 0 )
            return;
        Object[] resp = new Object[] { ReqID, (byte)ResultCode, ResultData };
        if ( (Flag & Context.FLAG_MESSAGE) != 0 ) {
            if (!JedisUtil.isInited())
                return;
            try {
                JedisUtil.publish(Context.CHANNEL_NOTIFY, resp);
            } catch (Exception e) {
                Bootstrap.log(LogUtil.ERROR, "message", e);
            }
        } else
            IOData.write(Sock, resp);
    }

    /* 获得服务所在目录 */
    static String getLocalPath(String name, String dir) {
        String path = Bootstrap.ServicePath + File.separator;
        name = Util.checkEmpty(name);
        if ( name != null )
            path += Bootstrap.MODULE_PATH + File.separator + name + File.separator;
        dir = Util.checkEmpty(dir);
        if ( dir != null )
            path += Config.repaireDir(dir) + File.separator;
        return path;
    }
    /** 获得服务所在目录 */
    public String getLocalPath() {
        return getLocalPath(Filter != null ? Filter : Service, null);
    }
    /** 获得服务所属文件 */
    public File getLocalFile(String filename) {
        return new File(getLocalPath() + filename);
    }
    /** 获得服务的属性文件 */
    public InputStream getResourceAsStream(String filename) {
        Filter module = Bootstrap.findModule(Filter != null ? Filter : Service);
        if ( module == null )
            return null;
        return module.JClass.getClassLoader().getResourceAsStream(filename);
    }
    /** 获得Consumer的地址 */
    public InetSocketAddress getConsumerAddress() {
        return (InetSocketAddress)Sock.remoteAddress();
    }
    /** 获得请求ID */
    public String getRequestID() {
        return ReqID;
    }
    /** 设置Header数据项 */
    public void setHeader(String key, Object value) {
        if ( Header == null )
            Header = new HashMap<String, Object>();
        Header.put(key, value);
    }
    /** 获取Header数据项 */
    public Object getHeader(String key) {
        return Header == null ? null : Header.get(key);
    }
    /** 获取Header数据对象 */
    public Map<String,Object> getHeader() {
        return Header;
    }
    /** 获得服务名字 */
    public String getServiceName() {
        return Service;
    }
    /** 获得服务状态 */
    public int getServiceStatus() {
        Filter module = Bootstrap.findModule(Service);
        if ( module == null )
            return 0;
        return module.Status;
    }
    /** 获得接口名字 */
    public String getEntryName() {
        return Entry;
    }
    /** 获得接口的注解 */
    public USEntry getEntryAnnotation() {
        if ( Entry == null )
            return null;
        Service srv = Bootstrap.ServiceMap.get(Service);
        if ( srv == null )
            return null;
        Service.Entry entry = srv.EntryMap.get(Entry);
        if ( entry == null )
            return null;
        return entry.JAnnotation;
    }
    /** 获得Container的ID */
    public String getContainerId() {
        return Bootstrap.Host + "#" + Bootstrap.Port;
    }
    /** 获得Container的版本 */
    public int getContainerVersion() {
        return Bootstrap.ServiceMap.get("").Version;
    }
    /** 获得Container的发行状态 */
    public boolean getContainerRelease() {
        return Bootstrap.ServiceMap.get("").Release;
    }
    /** 检查Container是否开始服务 */
    public boolean isContainerReady() {
        return Bootstrap.MainChannel != null;
    }
    /** 获得参数数量 */
    public int getParamCount() {
        return Param.length - 1;
    }
    /** 获得参数 */
    public Object getParam(int index) {
        return Param[index + 1];
    }
    /** 设置参数 */
    public void setParam(Object... o) {
        Param = new Object[o.length + 1];
        Param[0] = this;
        for ( int i = 0; i < o.length; i ++ )
            Param[i + 1] = o[i];
    }
    /** 获得请求标志 */
    public byte getRequestFlag() {
        return Flag;
    }

    /** 暂停服务 */
    public void pause() throws Exception {
        Filter module = Bootstrap.findModule(Filter != null ? Filter : Service);
        if ( module == null )
            return;
        if ( module.pause(true) ) {
            rewin.ubsi.container.Service.FlushRegister = true;
            Controller.saveModuleFile(new ServiceContext(""));
        }
    }
    /** 重启服务 */
    public void restart() throws Exception {
        final String name = Filter != null ? Filter : Service;
        final Filter module = Bootstrap.findModule(name);
        if ( module == null )
            return;
        new Thread(new Runnable() {
            public void run() {
                int status = module.Status;
                try {
                    module.stop(name);
                    module.start(name);
                } catch (Exception e) {
                    Bootstrap.log(LogUtil.ERROR, "restart " + name, e);
                } finally {
                    if ( status != module.Status ) {
                        rewin.ubsi.container.Service.FlushRegister = true;
                        try { Controller.saveModuleFile(new ServiceContext("")); } catch (Exception e) { }
                    }
                }
            }
        }).start();
    }

    /** 获得处理数量统计，[ over, error ] */
    public long[] getStatistics(String service, String entry) {
        Service srv = Bootstrap.ServiceMap.get(service);
        if ( srv == null )
            return null;
        Service.Entry srv_entry = srv.EntryMap.get(entry);
        if ( srv_entry == null )
            return null;
        return new long[] { srv_entry.RequestOver.get(), srv_entry.RequestError.get() };
    }
    /** 获得处理数量统计 */
    public Map<String, long[]> getStatistics(String service) {
        Service srv = Bootstrap.ServiceMap.get(service);
        if ( srv == null )
            return null;
        Map<String, long[]> res = new HashMap<>();
        for ( String entry : srv.EntryMap.keySet() )
            res.put(entry, getStatistics(service, entry));
        return res;
    }
    /** 获得处理数量统计 */
    public Map<String, Map<String, long[]>> getStatistics() {
        Map<String, Map<String, long[]>> res = new HashMap<>();
        for ( String service : Bootstrap.ServiceMap.keySet() )
            res.put(service, getStatistics(service));
        return res;
    }

    /** 设置返回结果 */
    public void setResultData(Object data) {
        Result = true;
        ResultCode = ErrorCode.OK;
        ResultData = data;
    }
    /** 设置错误结果 */
    public void setResultError(String msg) {
        Result = true;
        ResultCode = ErrorCode.ERROR;
        ResultData = msg;
    }
    /* 设置异常结果 */
    void setResultException(Exception e) {
        Result = true;
        ResultCode = ErrorCode.EXCEPTION;
        ResultData = Util.getTargetThrowable(e).toString();
    }
    /* 设置异常结果 */
    void setResult(int code, String msg) {
        Result = true;
        ResultCode = code;
        ResultData = msg;
    }
    /** 是否有处理结果 */
    public boolean hasResult() {
        return Result;
    }
    /** 获得结果代码 */
    public int getResultCode() {
        return ResultCode;
    }
    /** 获得结果数据 */
    public Object getResultData() {
        return ResultData;
    }
    /** 是否转发 */
    public boolean isForwarded() {
        return Forwarded;
    }

    /* 是否需要强制记录日志 */
    boolean isForceLog() {
        if ( LogUtil.LOG_SERVICE.equals(Service) )
            return false;       // 过滤对LOG_SERVICE的请求日志
        if ( (Flag & Context.FLAG_LOG) != 0 )
            return true;
        Config.LogAccess[] logForce = Bootstrap.LogForce;   // 防止变量重置
        if ( logForce == null || logForce.length == 0 )
            return false;
        for ( Config.LogAccess la : logForce )
            if (Util.matchString(Service, la.service) && Util.matchString(Entry, la.entry))
                return true;
        return false;
    }
    /** 创建Consumer请求对象 */
    public Context request(String service, Object... entryAndParams) throws Exception {
        Context context = Context.request(service, entryAndParams);
        Filter module = Bootstrap.findModule(Filter != null ? Filter : Service);
        if ( module != null ) {
            Filter.Depend depend = module.Dependency.get(service);
            if ( depend != null )
                context.setVersion(depend.VerMin, depend.VerMax, depend.Release);
        }
        if ( isForceLog() )
            context.setSeqID(ReqID);    // 强制记录跟踪请求日志
        return context;
    }
    /* 转发请求 */
    void forward() throws Exception {
        Context.forward(Sock, ReqID, Header, Service, Param, Flag, Bootstrap.Forward);
    }

    /** 保存数据文件(JSON格式) */
    public void saveDataFile(String filename, Object data) throws Exception {
        Util.saveJsonFile(getLocalFile(filename), data);
    }
    /** 读取数据文件(JSON格式)，文件不存在返回null */
    public <T> T readDataFile(String filename, Type type, Type... typeArguments) throws Exception {
        return Util.readJsonFile(getLocalFile(filename), type, typeArguments);
    }

    /** 获得日志记录器 */
    public Logger getLogger() {
        String type = "rewin.ubsi.service";
        String name = Service;
        if ( Filter != null ) {
            type = "rewin.ubsi.filter";
            name = Filter;
        } else if ( !Bootstrap.ServiceMap.containsKey(name) ) {
            type = "rewin.ubsi.filter";
        } else {
            if (name.isEmpty())
                name = "\"\"";
            if ( Util.checkEmpty(Entry) != null )
                name += "#" + Entry;
        }
        return Context.getLogger(type, name);
    }

    /** 是否系统的JAR包，忽略版本号 */
    public static boolean isSysLib(String group, String artifact, String ver) {
        return LibManager.isSysLib(group, artifact);
    }
}
