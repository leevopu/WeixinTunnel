package com.weixin.corp.main;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.weixin.corp.entity.AccessToken;
import com.weixin.corp.entity.message.json.CorpBaseJsonMessage;
import com.weixin.corp.entity.user.Department;
import com.weixin.corp.entity.user.User;
import com.weixin.corp.service.MessageService;
import com.weixin.corp.service.UserService;
import com.weixin.corp.utils.WeixinUtil;

public class TimerTaskServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static Log log = LogFactory.getLog(WeixinUtil.class);

	public void init() throws ServletException {
		// 获取web.xml中配置的参数
		// appid第三方用户唯一凭证
		String appid = getInitParameter("appid");
		// appsecret第三方用户唯一凭证密钥
		String appsecret = getInitParameter("appsecret");
		// aeskey第三方用户加密密钥
		String aeskey = getInitParameter("aeskey");
		// agentid第三方用户应用ID
		String agentid = getInitParameter("agentid");

		// 未配置appid、appsecret、aeskey时给出提示
		if ("".equals(appid) || "".equals(appsecret) || "".equals(aeskey)
				|| aeskey.length() != 43 || "".equals(agentid)) {
			log.error("appid, appsecret, aeskey or agentid configuration error in web.xml, please check carefully.");
			System.exit(-1);
		} else {
			// token第三方用户验证口令
			String token = getInitParameter("token");
			if (null != token) {
				WeixinUtil.init(token, appid, appsecret, aeskey, agentid);
			}

			// 启动定时获取跑批数据，每天10点触发1次进行群发
			dailyFixOnTimeTask(10, new DailyGroupMessageTimerTask());
			// 启动定时更新用户信息，每天6点触发1次更新缓存
			dailyFixOnTimeTask(6, new DailyUpdateUserTimerTask());
			// 首次初始化缓存
			Runnable userPoolInit = new DailyUpdateUserTimerTask();
			userPoolInit.run();
			// 启动循环获取access_token的线程，access_token每隔2小时会失效
			new Thread(new WeixinAccessTokenTimerTaskThread()).start();
			// 启动循环监控用户自定义发送时间的消息
			new Thread(new DelayJsonMessageTimerTaskThread()).start();
		}
	}

	/**
	 * 
	 * @param fixHour
	 *            0-23
	 * @param runnable
	 *            task
	 */
	public static void dailyFixOnTimeTask(int fixHour, Runnable runnable) {
		long oneDay = 24 * 60 * 60 * 1000;
		Calendar fixTime = Calendar.getInstance();
		fixTime.setTime(new Date());
		fixTime.set(Calendar.HOUR_OF_DAY, fixHour);
		fixTime.set(Calendar.MINUTE, 0);
		fixTime.set(Calendar.SECOND, 0);
		long initDelay = fixTime.getTimeInMillis() - System.currentTimeMillis();
		initDelay = initDelay > 0 ? initDelay : oneDay + initDelay;
		ScheduledExecutorService exec = Executors.newScheduledThreadPool(1);
		exec.scheduleAtFixedRate(runnable, initDelay, oneDay,
				TimeUnit.MILLISECONDS);
	}

	public static class DailyGroupMessageTimerTask implements Runnable {
		@Override
		public void run() {
			try {
				System.out.println("开始执行每日定时群发消息");
				// 模拟定时取数据，真实环境需连接数据库 groupMessagePool
				WeixinUtil.testFetchData();
				// 群发消息
				MessageService.groupMessage();
				// 未成功发送的记录会保留，可以进一步处理
				// 之前失败的消息通知管理员
				// MessageUtil.warnFailureMessage();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public static class DailyUpdateUserTimerTask implements Runnable {
		@Override
		public void run() {
//		测试			
//			Department department1 = new Department();
//			department1.setId("1");
//			department1.setName("财务");
//			department1.setOrder(1);
//			department1.setParentid(2);
//
//			Department department2 = new Department();
//			department2.setId("2");
//			department2.setName("后勤");
//			department2.setOrder(1);
//			department2.setParentid(2);
//
//			User user1 = new User();
//			user1.setDepartment("1");
//			user1.setMobile("13777777777");
//			user1.setUserid("431");
//
//			User user2 = new User();
//			user2.setDepartment("2");
//			user2.setMobile("13888888888");
//			user2.setUserid("567");
//
//			User user3 = new User();
//			user3.setDepartment("2");
//			user3.setMobile("13999999999");
//			user3.setUserid("617");
			
//			List<Department> departmentList = new ArrayList<Department>();
//			departmentList.add(department1);
//			departmentList.add(department2);
//			List<User> userList = new ArrayList<User>();
//			userList.add(user1);
//			userList.add(user2);
//			userList.add(user3);
			try {
				System.out.println("开始执行每日定时更新用户");

				// 获取微信全部部门信息
				 List<Department> departmentList =
				 UserService.getDepartment();
				 if (null == departmentList) {
				 return;
				 }
				// 遍历部门获取用户信息
				 List<User> userList = null;
				for (Department department : departmentList) {
					System.out.println(department.getId()+":"+department.getName());
					Map<String, HashMap<String, User>> maps =WeixinUtil.getUseridPool();
					// 有新增部门，放入缓存
					if (null == maps.get(department.getName())) {
						maps.put(department.getName(),new HashMap<String, User>());
					}
					//是否递归获取子部门下面的成员  1/0
					String feachChild = "1";
					//0获取全部员工，1获取已关注成员列表，2获取禁用成员列表，4获取未关注成员列表。status可叠加
					String status = "0";
					userList = UserService.getUserByDepartment(department.getId(),feachChild,status);
					if (null != userList) {
						// 清空用户缓存
						maps.get(department.getName()).clear();
						HashMap<String, User> datas = maps.get(department.getName());
						// 放入用户缓存
						for (User user : userList) {
							//user.getDepartment()是一个object数组 
							if (null != user.getMobile()&& !("".equals(user.getMobile())) ) {
							//WeixinUtil.getUseridPool().get(department.getName()).put(user.getMobile(), user);
							datas.put(user.getMobile(), user);
							}
						}
					}
				}
				System.out.println("用户信息缓存更新完成");
				log.info("用户信息缓存更新完成");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 定时获取微信access_token的线程
	 * 
	 */
	public static class WeixinAccessTokenTimerTaskThread implements Runnable {

		public void run() {
			while (true) {
				try {
					AccessToken accessToken = WeixinUtil.getNewAccessToken();
					if (null != accessToken) {
						log.info(String.format(
								"获取access_token成功，有效时长%d秒 token:%s",
								accessToken.getExpiresIn(),
								accessToken.getToken()));
						// 休眠到过期前200秒再去获取新的accessToken
						Thread.sleep((accessToken.getExpiresIn() - 200) * 1000);
					} else {
						// 如果access_token为null，60秒后再获取
						Thread.sleep(60 * 1000);
					}
				} catch (InterruptedException e) {
					try {
						Thread.sleep(60 * 1000);
					} catch (InterruptedException e1) {
						log.error("{}", e1);
					}
					log.error("{}", e);
				}
			}
		}
	}

	/**
	 * 定时发送json消息的线程
	 * 
	 */
	public static class DelayJsonMessageTimerTaskThread implements Runnable {

		@Override
		public void run() {
			while (true) {
				try {
					CorpBaseJsonMessage jsonMessage = WeixinUtil
							.getDelayJsonMessageQueue().take();
					// 定时触发响应，不论是否成功
					MessageService.sendMessage(jsonMessage);
					if(jsonMessage.isPermanent()){
						// 删除永久库素材消息
						MessageService.deletePermanentMedia(jsonMessage.getMediaId());
					}
				} catch (Throwable e) {
					e.printStackTrace();
				}
			}

		}

	}

	public static void main(String[] args) {
		DailyUpdateUserTimerTask x = new DailyUpdateUserTimerTask();
		x.run();
	}
}
