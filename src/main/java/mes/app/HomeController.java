package mes.app;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import mes.app.notification.NotificationService;
import mes.app.system.service.UserService;
import mes.domain.entity.Notification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

import mes.config.Settings;
import mes.domain.entity.SystemOption;
import mes.domain.entity.User;
import mes.domain.repository.SystemOptionRepository;


@Controller
public class HomeController {
	
	@Autowired
	SystemOptionRepository systemOptionRepository;
	
	@Autowired
	Settings settings;

	@Autowired
	UserService userService;

	@Autowired
	NotificationService notificationService;

	@RequestMapping(value= "/", method=RequestMethod.GET)
    public ModelAndView pageIndex(HttpServletRequest request, HttpSession session, HttpServletResponse response) {

		// User-Agent 확인
		String userAgent = request.getHeader("User-Agent").toLowerCase();
		boolean isMobile = userAgent.contains("mobile") || userAgent.contains("iphone");

		String serverName = request.getServerName();

//		if (isMobile && serverName.equalsIgnoreCase("actascld.co.kr")) {
//			String redirectUrl = "https://mes.actascld.co.kr";
//			try {
//				response.sendRedirect(redirectUrl);
//				return null; // redirect 했으므로 이후 처리 중단
//			} catch (IOException e) {
//				e.printStackTrace(); // 로그로 출력하거나, 에러 뷰로 포워딩도 가능
//				return new ModelAndView("error/redirect_error"); // 예외 시 fallback 처리
//			}
//		}

        SecurityContext sc = SecurityContextHolder.getContext();
        Authentication auth = sc.getAuthentication();         
        User user = (User)auth.getPrincipal();
        String username = user.getUserProfile().getName();
		String userid = user.getUsername();
		Integer groupid = user.getUserProfile().getUserGroup().getId();
		String groupname = user.getUserProfile().getUserGroup().getName();
		Integer factory_id = user.getUserProfile().getFactoryId();
		String spjangcd = user.getSpjangcd();
                
        SystemOption sysOpt= this.systemOptionRepository.getByCode("LOGO_TITLE");
        String logoTitle = sysOpt.getValue();
        
        //q = this.systemOptionRepository.getByCode("main_menu");        

		List<Map<String, Object>> spjangList = null;
		if (groupid == 1){ //관리자
			spjangList = userService.getSpjangList();
		} else {
			spjangList = userService.getSpjang(spjangcd);
		}

		ModelAndView mv = new ModelAndView();
		session.setAttribute("spjangList", spjangList);
		mv.addObject("username", username);
		mv.addObject("userid", userid);
		mv.addObject("groupid", groupid);
		mv.addObject("groupname", groupname);
		mv.addObject("factory_id", factory_id);
		session.setAttribute("userid", userid);
		session.setAttribute("username", username);
		session.setAttribute("groupid", groupid);
		session.setAttribute("factory_id", factory_id);
		session.setAttribute("spjangcd", spjangcd);
		mv.addObject("userinfo", user);
		mv.addObject("system_title", logoTitle);
		mv.addObject("default_menu_code", "wm_dashboard_summary");

		// 안읽은 알람
		List<Notification> unreadList = notificationService.getUnread(userid, spjangcd);
		mv.addObject("unreadAlarmCount", unreadList.size());
		mv.addObject("unreadAlarmList", unreadList);
		
		String mqtt_host = settings.getProperty("mqtt_host");
		String mqtt_web_port = settings.getProperty("mqtt_web_port");
		String hmi_topic = settings.getProperty("hmi_topic");
		mv.addObject("mqtt_host", mqtt_host);
		mv.addObject("mqtt_web_port", mqtt_web_port);
		mv.addObject("hmi_topic", hmi_topic);

//		mv.setViewName(isMobile ? "mobile/mobile_main" : "index");
		mv.setViewName("index");
		
		return mv;
	}

	@RequestMapping(value= "/mobile_pro/MobileFirstPage", method=RequestMethod.GET)
	public ModelAndView pageMobileFirstPage(HttpServletRequest request, HttpSession session) {

		// User-Agent 확인
		String userAgent = request.getHeader("User-Agent").toLowerCase();
		boolean isMobile = userAgent.contains("mobile") || userAgent.contains("iphone");

		SecurityContext sc = SecurityContextHolder.getContext();
		Authentication auth = sc.getAuthentication();
		User user = (User)auth.getPrincipal();
		String username = user.getUserProfile().getName();
		String userid = user.getUsername();
		Integer groupid = user.getUserProfile().getUserGroup().getId();
		String groupname = user.getUserProfile().getUserGroup().getName();
		String spjangcd = user.getSpjangcd();

		SystemOption sysOpt= this.systemOptionRepository.getByCode("LOGO_TITLE");
		String logoTitle = sysOpt.getValue();

		//q = this.systemOptionRepository.getByCode("main_menu");

		List<Map<String, Object>> spjangList = null;
		if (groupid == 1){ //관리자
			spjangList = userService.getSpjangList();
		} else {
			spjangList = userService.getSpjang(spjangcd);
		}

		ModelAndView mv = new ModelAndView();
		session.setAttribute("spjangList", spjangList);
		mv.addObject("username", username);
		mv.addObject("userid", userid);
		mv.addObject("groupname", groupname);
		session.setAttribute("spjangcd", spjangcd);
		mv.addObject("userinfo", user);
		mv.addObject("system_title", logoTitle);
		mv.addObject("default_menu_code", "wm_dashboard_summary");


		String mqtt_host = settings.getProperty("mqtt_host");
		String mqtt_web_port = settings.getProperty("mqtt_web_port");
		String hmi_topic = settings.getProperty("hmi_topic");
		mv.addObject("mqtt_host", mqtt_host);
		mv.addObject("mqtt_web_port", mqtt_web_port);
		mv.addObject("hmi_topic", hmi_topic);

		mv.setViewName("mobile_pro/MobileFirstPage");

		return mv;
	}
	@RequestMapping(value= "/intro", method=RequestMethod.GET)
    public ModelAndView pageIntro(HttpServletRequest request, HttpSession session) {
		ModelAndView mv = new ModelAndView();
		mv.setViewName("intro");
		return mv;
	}
	

	@RequestMapping(value= "/setup", method=RequestMethod.GET)
	public ModelAndView pageSetup(Authentication auth, HttpServletResponse response) throws IOException {
		
		// 로그아웃된 상태인 경우 로그인페이지로 이동
		if (auth == null) {
		    response.sendRedirect("/login");
			return null;
		} 
		
		User user = (User)auth.getPrincipal();
		String username = user.getUserProfile().getName();
		
		ModelAndView mv = new ModelAndView();
		mv.addObject("username", username);
		mv.addObject("userinfo", user);
		
		mv.setViewName("/system/setup");
		return mv;
	}
	
		
	
}