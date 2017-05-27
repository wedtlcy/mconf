package cn.ms.mconf.zookeeper;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.CuratorFrameworkFactory.Builder;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCache.StartMode;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.ms.mconf.support.AbstractMconf;
import cn.ms.mconf.support.Category;
import cn.ms.mconf.support.MParamType;
import cn.ms.mconf.support.MetaData;
import cn.ms.mconf.support.NotifyConf;
import cn.ms.micro.common.ConcurrentHashSet;
import cn.ms.micro.common.URL;
import cn.ms.micro.extension.SpiMeta;

import com.alibaba.fastjson.JSON;

/**
 * The base of Zookeeper Mconf.
 * 
 * @author lry
 */
@SpiMeta(name = "zookeeper")
public class ZookeeperMconf extends AbstractMconf {

	private static final Logger logger = LoggerFactory.getLogger(ZookeeperMconf.class);
	
	private String group;
	
	private CuratorFramework client;
	private ConnectionState globalState = null;
	
	private final ExecutorService pool = Executors.newFixedThreadPool(2);
	@SuppressWarnings("rawtypes")
	private final Map<String, Set<NotifyConf>> pushNotifyConfMap = new ConcurrentHashMap<String, Set<NotifyConf>>();
	private final Map<String, Map<String, Object>> pushMap = new ConcurrentHashMap<String, Map<String, Object>>();
	private final Map<String, PathChildrenCache> pathChildrenCacheMap = new ConcurrentHashMap<String, PathChildrenCache>();
	
	@Override
	public void connect(URL url) {
		super.connect(url);
		this.group = url.getParameter(MParamType.GROUP.getName(), MParamType.GROUP.getValue());
		
		String connAddrs = url.getBackupAddress();
		int timeout = url.getParameter(MParamType.TIMEOUT.getName(), MParamType.TIMEOUT.getIntValue());
		int session = url.getParameter(MParamType.SESSION.getName(), MParamType.SESSION.getIntValue());
		
		Builder builder = CuratorFrameworkFactory.builder().connectString(connAddrs)
				.retryPolicy(new RetryNTimes(Integer.MAX_VALUE, 1000)).connectionTimeoutMs(timeout).sessionTimeoutMs(session);
		final CountDownLatch cd = new CountDownLatch(1);
		client = builder.build();
		client.getConnectionStateListenable().addListener(
				new ConnectionStateListener() {
					public void stateChanged(CuratorFramework client, ConnectionState state) {
						logger.info("The registration center connection status is changed to [{}]", state);
						if(globalState == null || state == ConnectionState.CONNECTED) {
							cd.countDown();
							globalState = state;
						}
					}
				});
		client.start();
		
		try {
			cd.await(timeout, TimeUnit.MILLISECONDS);
			if(ConnectionState.CONNECTED != globalState){
				throw new TimeoutException("The connection zookeeper is timeout.");
			}
		} catch (Exception e) {
			logger.error("The await exception.", e);
		}
	}

	@Override
	public boolean available() {
		return client.getZookeeperClient().isConnected();
	}

	@Override
	public <T> void addConf(T data) {
		this.addConf(category, data);
	}
	
	@Override
	public <T> void addConf(Category category, T data) {
		MetaData metaData = this.obj2MetaData(data, category);
		String path = this.buildPath(metaData);
		byte[] dataByte = null;
		try {
			dataByte = String.valueOf(metaData.getBody()).getBytes(Charset.forName("UTF-8"));
		} catch (Exception e) {
			throw new IllegalStateException("Serialized data exception", e);
		}

		try {
			client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path, dataByte);
		} catch (NodeExistsException e) {
		} catch (Exception e) {
			throw new IllegalStateException("Add data exception", e);
		}
	}

	@Override
	public <T> void delConf(T data) {
		this.delConf(category, data);
	}
	
	@Override
	public <T> void delConf(Category category, T data) {
		MetaData metaData = this.obj2MetaData(data, category);
		String path = this.buildPath(metaData);
		
		try {
			client.delete().forPath(path);
		} catch (NoNodeException e) {
		} catch (Exception e) {
			throw new IllegalStateException("Delete data exception", e);
		}
	}

	@Override
	public <T> void setConf(T data) {
		this.setConf(category, data);
	}

	@Override
	public <T> void setConf(Category category, T data) {
		MetaData metaData = this.obj2MetaData(data, category);
		String path = this.buildPath(metaData);
		
		byte[] dataByte = null;
		try {
			dataByte = String.valueOf(metaData.getBody()).getBytes(Charset.forName("UTF-8"));
		} catch (Exception e) {
			throw new IllegalStateException("Serialized data exception", e);
		}

		try {
			client.setData().forPath(path, dataByte);
		} catch (NoNodeException e) {
		} catch (Exception e) {
			throw new IllegalStateException("Modify data exception", e);
		}
	}
	
	@Override
	public <T> T pull(T data) {
		return this.pull(category, data);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T pull(Category category, T data) {
		MetaData metaData = this.obj2MetaData(data, category);
		String path = this.buildPath(metaData);
		
		byte[] dataByte = null;

		try {
			dataByte = client.getData().forPath(path);
		} catch (NoNodeException e) {
		} catch (Exception e) {
			throw new IllegalStateException("Modify data exception", e);
		}

		if (dataByte == null) {
			return null;
		}

		try {
			String json = new String(dataByte, Charset.forName("UTF-8"));
			return (T)json2Obj(json, data.getClass());
		} catch (Exception e) {
			throw new IllegalStateException("UnSerialized data exception", e);
		}
	}
	
	@Override
	public <T> List<T> pulls(T data) {
		return this.pulls(category, data);
	}
	
	@Override
	public <T> List<T> pulls(Category category, T data) {
		MetaData metaData = this.obj2MetaData(data, category);
		List<T> list = new ArrayList<T>();
		metaData.setBody(null);// Force setting dataId to Nulls
		
		//Query all dataId lists
		List<String> childNodeList= null;
		try {
			String path = this.buildPath(metaData);
			childNodeList = client.getChildren().forPath(path);
		} catch (NoNodeException e) {
		} catch (Exception e) {
			throw new IllegalStateException("Gets all child node exceptions", e);
		}
		
		if(childNodeList!=null){
			for (String childNode:childNodeList) {
				String dataId = decode(childNode);
				if(StringUtils.isBlank(dataId)){
					throw new RuntimeException("Invalid data, dataId=="+dataId);
				}
				if(dataId.indexOf("?") > 0){
					metaData.setData(dataId.substring(0, dataId.indexOf("?")));
				}
				
				String path = this.buildPath(metaData);
				String json;
				byte[] dataByte = null;

				try {
					dataByte = client.getData().forPath(path);
				} catch (NoNodeException e) {
				} catch (Exception e) {
					throw new IllegalStateException("Modify data exception", e);
				}

				if (dataByte == null) {
					return null;
				}

				try {
					json = new String(dataByte, Charset.forName("UTF-8"));
				} catch (Exception e) {
					throw new IllegalStateException("UnSerialized data exception", e);
				}
				
				
				@SuppressWarnings("unchecked")
				T t = (T)json2Obj(json, data.getClass());
				if(t!=null){
					list.add(t);
				}
			}
		}
		
		return list;
	}
	
	@Override
	public <T> void push(final T data, final NotifyConf<T> notifyConf) {
		this.push(category, data, notifyConf);
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public <T> void push(Category category, final T data, final NotifyConf<T> notifyConf) {
		MetaData metaData = this.obj2MetaData(data, category);
		final String path = this.buildPath(metaData);
		if(StringUtils.isBlank(path)){
			throw new RuntimeException("PATH cannot be empty, path=="+path);
		}
		
		//允许多个监听者监听同一个节点
		Set<NotifyConf> notifyConfs = pushNotifyConfMap.get(path);
		if(notifyConfs == null){
			pushNotifyConfMap.put(path, notifyConfs = new ConcurrentHashSet<NotifyConf>());
		}
		notifyConfs.add(notifyConf);
		
		if(pushMap.containsKey(path)){//已被订阅
			List list = new ArrayList();
			list.addAll(pushMap.get(path).values());
			notifyConf.notify(list);//通知一次
		} else {
			final Map<String, Object> tempMap;
			pushMap.put(path, tempMap = new ConcurrentHashMap<String, Object>());
			
			try {
				final PathChildrenCache childrenCache = new PathChildrenCache(client, path, true);
				childrenCache.start(StartMode.POST_INITIALIZED_EVENT);
				childrenCache.getListenable().addListener(
					new PathChildrenCacheListener() {
						private boolean isInit = false;
						@Override
						public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
							ChildData childData = event.getData();
							
							if(event.getInitialData() != null){
								isInit = true;
							}
							
							if (childData == null) {
								logger.debug("The is listenering PATH[{}], initialization notify all data[{}].", path, JSON.toJSONString(tempMap));
							} else {
								String tempPath = event.getData().getPath();
								String tempJsonData = new String(event.getData().getData(), Charset.forName("UTF-8"));
								T t = (T)JSON.parseObject(tempJsonData, data.getClass());
								
								if(PathChildrenCacheEvent.Type.CHILD_ADDED == event.getType()
										|| PathChildrenCacheEvent.Type.CHILD_UPDATED == event.getType()){
									tempMap.put(tempPath, t);
								} else if(PathChildrenCacheEvent.Type.CHILD_REMOVED == event.getType()){
									tempMap.remove(tempPath);
								}
								
								if(isInit){
									logger.debug("The changed PATH[{}] update data[{}].", tempPath, event.getType(), tempJsonData);
									logger.debug("The changed PATH[{}] notify all datas[{}].", path, JSON.toJSONString(tempMap));
									Set<NotifyConf> tempNotifyConfSet = pushNotifyConfMap.get(path);
									for (NotifyConf tempNotifyConf:tempNotifyConfSet) {//通知每一个监听器
										List list = new ArrayList();
										list.addAll(tempMap.values());
										tempNotifyConf.notify(list);
									}									
								}
							}
						}
					}, pool);
				pathChildrenCacheMap.put(path, childrenCache);
			} catch (Exception e) {
				logger.error("The PathChildrenCache add listener exception.", e);
			}
		}
	}
	
	@Override
	public <T> void unpush(T data) {
		this.unpush(category, data);
	}
	
	@Override
	public <T> void unpush(Category category, T data) {
		MetaData metaData = this.obj2MetaData(data, category);
		String path = this.buildPath(metaData);
		if(StringUtils.isBlank(path)){
			throw new RuntimeException("PATH cannot be empty, path=="+path);
		}
		
		PathChildrenCache pathChildrenCache = pathChildrenCacheMap.get(path);
		if(pathChildrenCache!=null){
			try {
				pathChildrenCache.close();
			} catch (IOException e) {
				logger.error("PathChildrenCache close exception", e);
			}
		}
		
		if (pushNotifyConfMap.containsKey(path)) {
			pushNotifyConfMap.remove(path);
		}
		
		if (pushMap.containsKey(path)) {
			pushMap.remove(path);
		}
	}
	
	@Override
	public <T> void unpush(T data, NotifyConf<T> notifyConf) {
		this.unpush(category, data, notifyConf);
	}
	
	@Override
	public <T> void unpush(Category category, T data, NotifyConf<T> notifyConf) {
		MetaData metaData = this.obj2MetaData(data, category);
		String path = this.buildPath(metaData);
		if(StringUtils.isBlank(path)){
			throw new RuntimeException("PATH cannot be empty, path=="+path);
		}
		
		PathChildrenCache pathChildrenCache = pathChildrenCacheMap.get(path);
		if(pathChildrenCache!=null){
			try {
				pathChildrenCache.close();
			} catch (IOException e) {
				logger.error("PathChildrenCache close exception", e);
			}
		}

		@SuppressWarnings("rawtypes")
		Set<NotifyConf> notifyConfs = pushNotifyConfMap.get(path);
		if (notifyConfs != null) {
			if (notifyConfs.contains(notifyConf)) {
				notifyConfs.remove(notifyConf);
			}
		}
		
		if (pushNotifyConfMap.get(path) == null) {
			pushMap.remove(path);
		}
	}
	
	/**
	 * The data structure: /[group]/[appId]/[confId]/[dataId]
	 * 
	 * @param metaData
	 * @return
	 */
	private String buildPath(MetaData metaData) {
		StringBuffer stringBuffer = new StringBuffer();
		stringBuffer.append("/").append(this.encode(group) + "-" + this.encode(metaData.getNode()));// 1
		
		if (StringUtils.isNotBlank(metaData.getApp())) {
			stringBuffer.append("/").append(this.encode(metaData.getApp()));// 2
		}
		if (StringUtils.isNotBlank(metaData.getConf())) {
			stringBuffer.append("/").append(this.encode(metaData.getConf()));// 3
		}
		if (StringUtils.isNotBlank(metaData.getData())) {// 4
			stringBuffer.append("/").append(this.encode(metaData.toBuildDataId()));
		}

		return stringBuffer.toString();
	}
	
	/**
	 * Data encoding
	 * 
	 * @param data
	 * @return
	 */
	private String encode(String data) {
		try {
			return URLEncoder.encode(data, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException("Encoding exception", e);
		}
	}
	
	/**
	 * Data decoding
	 * 
	 * @param data
	 * @return
	 */
	private String decode(String data) {
		try {
			return URLDecoder.decode(data, MParamType.DEFAULT_CHARTSET);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException("Decoding exception", e);
		}
	}

}
