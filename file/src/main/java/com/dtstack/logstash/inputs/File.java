package com.dtstack.logstash.inputs;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import com.dtstack.logstash.annotation.Required;
import com.dtstack.logstash.assembly.InputQueueList;
import com.dtstack.logstash.decoder.IDecode;
import com.dtstack.logstash.inputs.BaseInput;
import com.google.common.collect.Lists;

/**
 * jlogstash 文件类型的读入插件
 * @author xuchao
 *
 */
public class File extends BaseInput{
	
	private static final long serialVersionUID = -1822028651072758886L;

	private static final Logger logger = LoggerFactory.getLogger(File.class);
	
	private static String encoding = "UTF-8";
	
	@Required(required=true)
	public static List<String> path = null;
	
	private static List<String> exclude = null;
	
	private static int maxOpenFiles = 0;//0表示没有上限
		
	private static String startPosition = "end";//one of ["beginning", "end"]
	
	/**文件当前读取位置点*/
	private ConcurrentHashMap<String, Integer> fileCurrPos  = new ConcurrentHashMap<String, Integer>();
	
	private static String sinceDbPath = "./sincedb.yaml";
	
	private static int sinceDbWriteInterval = 15; //sincedb.yaml 更新频率(时间s)
	
	/**当读取设置行之后更新当前文件读取位置*/
	private static int readLineNum4UpdateMap = 1000;
	
	private static byte delimiter = '\n';
	
	private List<String> realPaths = Lists.newArrayList();
	
	private BlockingQueue<String> needReadList = new LinkedBlockingQueue<String>();
	
	private ConcurrentHashMap<String, Long> monitorMap = new ConcurrentHashMap<String, Long>();
	
	private boolean runFlag = false;
	
	private ExecutorService executor;
	
	private ScheduledExecutorService scheduleExecutor;
	
	private ReentrantLock writeFileLock = new ReentrantLock();

	public File(Map config, InputQueueList inputQueueList) {
		super(config, inputQueueList);
	}

	@Override
	public void prepare() {
		
		if(realPaths.size() == 0){
			List<String> ps = Lists.newArrayList();
			for(String p : path){
				java.io.File file = new java.io.File(p);
				if(!file.exists()){
					logger.error("file:{} is not exists.", p);
					System.exit(1);
				}
				
				if(file.isDirectory()){
					ps.addAll(Arrays.asList(file.list()));
				}else{
					ps.add(p);
				}
			}
			
			filterExcludeFile(ps, true);
			
			realPaths.addAll(ps);
			if(maxOpenFiles > 0 && realPaths.size() > maxOpenFiles){
				logger.error("file numbers is exceed.");
				System.exit(1);
			}
			
			needReadList.addAll(realPaths);
		}
		
		checkoutSinceDb();
		runFlag = true;
		ReadLineUtil.setDelimiter(delimiter);
	}
	
	public void filterExcludeFile(List<String> fileList, boolean errExit){
		if(exclude != null){
			for(String e : exclude){
				java.io.File file = new java.io.File(e);
				if(!file.exists()){
					logger.error("exclude file:{} is not exists.", e);
					
					if(errExit){
						System.exit(1);
					}
				}
				
				if(file.isDirectory()){
					fileList.removeAll(Arrays.asList(file.list()));
				}else{
					fileList.remove(e);
				}
			}
		}
	}
	
	private void checkoutSinceDb(){
		Yaml yaml = new Yaml();
		java.io.File sinceFile = new java.io.File(sinceDbPath);
		if(!sinceFile.exists()){
			return;
		}
		
		InputStream io = null;
		try {
			io = new FileInputStream(sinceFile);
			Map<String, Integer> fileMap = (Map) yaml.load(io);
			if(fileMap == null){
				return;
			}
			
			fileCurrPos.putAll(fileMap);
		} catch (FileNotFoundException e) {
			logger.error("open file:{} err:{}!", sinceDbPath, e.getCause());
			System.exit(1);
		}finally{
			if(io != null){
				try {
					io.close();
				} catch (IOException e) {
					logger.error("", e);
				}
			}
		}
		
	}
	
	private void dumpSinceDb(){
		
		FileWriter fw = null;
		try{
			writeFileLock.lock();
			Yaml yaml = new Yaml();
			fw = new FileWriter(sinceDbPath);
			yaml.dump(fileCurrPos, fw);
		}catch(Exception e){
			logger.error("", e);
			logger.info("curr file pos:{}", fileCurrPos);
		}finally{
			try {
				fw.close();
			} catch (IOException e) {
				logger.error("", e);
			}
			
			writeFileLock.unlock();
		}
		
	}

	@Override
	public void emit() {
		executor = Executors.newFixedThreadPool(3);
		scheduleExecutor = Executors.newScheduledThreadPool(1);
		executor.submit(new MonitorChangeRunnable());
		executor.submit(new MonitorNewFileRunnable());
		executor.submit(new FileRunnable(this));
		scheduleExecutor.scheduleWithFixedDelay(new DumpSinceDbRunnable(), 
				sinceDbWriteInterval, sinceDbWriteInterval, TimeUnit.SECONDS);
	}

	@Override
	public void release() {
		//在正常关闭的时候记录offset
		executor.shutdownNow();
		scheduleExecutor.shutdownNow();
		dumpSinceDb();
	}
	
	class FileRunnable implements Runnable{
		
		private IDecode decoder;
		
		private File fileInput;
		
		public FileRunnable(File fileInput) {
			this.fileInput = fileInput;
			this.decoder = this.fileInput.createDecoder();
		}

		public void run() {
			
			while(runFlag){
				try {
					String readFileName = needReadList.poll(10, TimeUnit.SECONDS);
					if(readFileName == null){
						continue;
					}
					
					java.io.File readFile = new java.io.File(readFileName);
					if(!readFile.exists()){
						logger.error("file:{} is not exists!", readFileName);
						continue;
					}
					
					long lastModTime = readFile.lastModified();
					Integer filePos = fileInput.fileCurrPos.get(readFileName);
					ReadLineUtil readLineUtil = null;
					
					if(filePos == null){
						readLineUtil = new ReadLineUtil(readFile, File.encoding, startPosition);
					}else{
						readLineUtil = new ReadLineUtil(readFile, File.encoding, filePos);
					}
					
					String line = null;
					int readLineNum = 0;
					
					while( (line = readLineUtil.readLine()) != null){
						readLineNum++;
						Map<String, Object> event = this.decoder.decode(line);
						if (event != null && event.size() > 0){
							this.fileInput.inputQueueList.put(event);
						}
						
						if(readLineNum%readLineNum4UpdateMap == 0){
							fileCurrPos.put(readFileName, readLineUtil.getCurrBufPos());
						}
					}
					
					System.out.println("has readLineNum:"  + readLineNum);
					
					fileCurrPos.put(readFileName, readLineUtil.getCurrBufPos());
					monitorMap.put(readFileName, lastModTime);
				} catch (Exception e) {
					logger.error("", e);
				}
			}
		}
		
	}
	
	/**
	 * 监控文件变化,将有变化的文件插入到needReadList里
	 * 2s查看一次
	 * @author xuchao
	 *
	 */
	class MonitorChangeRunnable implements Runnable{

		public void run() {
			
			while(runFlag){
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					logger.error("", e);
				}
				
				Iterator<Entry<String, Long>> iterator = monitorMap.entrySet().iterator();
				for( ;iterator.hasNext(); ){
					Entry<String, Long> entry = iterator.next();
					java.io.File monitorFile = new java.io.File(entry.getKey());
					if(!monitorFile.exists()){
						logger.error("file:{} not exists!", entry.getKey());
						continue;
					}
					
					if(monitorFile.lastModified() > entry.getValue()){
						needReadList.offer(entry.getKey());
						iterator.remove();
					}
				}
				
			}
		}
		
	}
	
	/** 
	 * 监控新出现的符合条件的文件,并加入到needReadList里
	 * @author xuchao
	 *
	 */
	class MonitorNewFileRunnable implements Runnable{

		public void run() {
			
			while(runFlag){
				
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e) {
					logger.error("", e);
				}
				
				boolean hasDic = false;
				for(String fileName : path){
					java.io.File file = new java.io.File(fileName);
					List<String> dicFileList = Arrays.asList(file.list());
					
					if(file.exists() && file.isDirectory()){
						hasDic = true;
						filterExcludeFile(dicFileList, false);
					}
					
					for(String addFile : dicFileList){
						needReadList.offer(addFile);
					}
				}
				
				if(!hasDic){
					logger.info("don't have any dictory,no need for monitor dictory add new file!");
					break;
				}
			}
			
		}
		
	}
	
	class DumpSinceDbRunnable implements Runnable{

		public void run() {
			dumpSinceDb();
		}
		
	}

}