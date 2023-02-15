package com.collby.servlet;

import com.collby.annotation.*;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;

public class MvcServlet extends HttpServlet{

    // 配置
    private final Properties properties = new Properties();

    // 扫描路径
    private String scanPackage = null;

    // 类路径
    private final List<String> classes = new ArrayList<>();

    // IOC容器
    private final Map<String, Object> ioc = new HashMap<>();

    private final List<HandlerMapper> handlerMapping = new ArrayList<>();

    @Override
    public void init(ServletConfig config) throws ServletException {

        System.out.println("hello spring_mvc");

        //初始化加载配置文件，通常是application.xml,这里用properties代替
        doLoadConfig(config.getInitParameter("contextConfigLocation"));

        // 扫描对应路径下的类，和接口并记录下对应路径
        // doScanClassPath(properties.getProperty("ScanPackage"));
        doScanClassPath(scanPackage);

        //初始化这些类，并放在一个IOC容器
        doInitContainer();

        //进行依赖注入,这里没考虑循环依赖
        doAutowired();

        //构造RequestMapping映射关系，将一个url映射为一个method
        InitRequestMapping();

        //等待用户请求，然后匹配url,定位方法，然后反射执行

        //返回结果
    }


    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doDispatcher(req, resp);
    }


    private void doLoadConfig(String location) {
        if (location.contains("classpath")) {
            if (location.contains("properties")) {
                InputStream fis = this.getClass().getClassLoader().getResourceAsStream(location.replaceAll("classpath:", ""));
                try {
                    properties.load(fis);
                    if (fis != null) {
                        fis.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (location.contains("xml")) {
                InputStream fis = this.getClass().getClassLoader().getResourceAsStream(location.replaceAll("classpath:", ""));
                SAXReader reader = new SAXReader();
                Document doc = null;
                try {
                    doc = reader.read(fis);
                } catch (DocumentException e) {
                    e.printStackTrace();
                }
                if (doc != null) {
                    Element rootElement = doc.getRootElement();
                    Element local = rootElement.element("PACKAGE");
                    scanPackage = local.getText().trim();
                }

            }
        }

    }

    private void doScanClassPath(String packageName) {
        URL url = this.getClass().getClassLoader().getResource("/" + packageName.replaceAll("\\.", "/"));
        if (url != null) {
            File resource = new File(url.getFile());
            File[] files = resource.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        // 递归扫文件
                        doScanClassPath(packageName + "." + file.getName());
                    } else {
                        String className = packageName + "." + file.getName().replaceAll(".class", "");
                        //
                        classes.add(className);
                    }
                }

            }
        }
    }

    private void doInitContainer() {
        if (classes.isEmpty()) {
            return;
        }
        try {
            for (String className : classes) {
                Class<?> clazz = Class.forName(className);
                //扫描所有的@Contorller @Service
                if (clazz.isAnnotationPresent(Controller.class)) {
                    String beanName = lowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName, clazz.newInstance());

                } else if (clazz.isAnnotationPresent(Service.class)) {
                    Object service = clazz.newInstance();//单例
                    Service s = clazz.getAnnotation(Service.class);
                    String beanName = s.value();
                    if (!"".equals(beanName.trim())) {
                        ioc.put(beanName, service);
                    } else {
                        beanName = lowerFirstCase(clazz.getSimpleName());
                        ioc.put(beanName, service);
                    }

                    Class<?>[] interfaces = clazz.getInterfaces();
                    for (Class<?> itf : interfaces) {
                        beanName = lowerFirstCase(itf.getSimpleName());
                        ioc.put(beanName, service);
                    }
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void doAutowired() {
        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
//            System.out.println(entry.getKey());
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {
                if (field.isAnnotationPresent(Autowired.class)) {
                    Autowired autowired = field.getAnnotation(Autowired.class);
                    String beanName = autowired.value().trim();
                    if ("".equals(beanName)) {
                        beanName = field.getType().getSimpleName();
                    }
                    //暴力去私有化属性
                    field.setAccessible(true);
                    try {
                        field.set(entry.getValue(), ioc.get(lowerFirstCase(beanName)));
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void InitRequestMapping() {
        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            if (clazz.isAnnotationPresent(Controller.class)) {
                String url = "";
                if (clazz.isAnnotationPresent(RequestMapping.class)) {
                    RequestMapping requestMapping = clazz.getAnnotation(RequestMapping.class);
                    url = requestMapping.value();
                }

                Method[] methods = clazz.getMethods();
                for (Method method : methods) {
                    if (method.isAnnotationPresent(RequestMapping.class)) {
                        RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
                        String regex = ("/" + url + requestMapping.value()).replaceAll("/+", "/");
                        handlerMapping.add(new HandlerMapper(entry.getValue(), method, regex));
//                        System.out.println("mapping" + regex +","+method);
                    }
                }

            }
        }
    }


    private void doDispatcher(HttpServletRequest req, HttpServletResponse resp){

        String url = req.getRequestURI();
        Map<String, String[]> parameterMap = req.getParameterMap();
        Integer paramCount = parameterMap.size();
        for (HandlerMapper HandlerMapper : handlerMapping) {
            if (Pattern.matches(HandlerMapper.regex, url)) {
                System.out.println("匹配成功");
                if (HandlerMapper.paramCount.equals(paramCount)) {
                    // 设置参数并且执行对应的Controller的方法
                    HandlerMapper.setReqParamMap(parameterMap);
                    HandlerMapper.setAttributeAndInvoke();
                    break;
                }
            }
        }
    }

    //首字母小写
    private String lowerFirstCase(String str) {
        char[] chars = str.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);

    }

    private static class HandlerMapper {
        private final Object controller;
        private final Method method;
        private final String regex;
        private Integer paramCount;
        private Map<String, String[]> reqParamMap = null;
        private final Map<String, Integer> paramIndexMapping; //参数顺序

        private Map<String, String[]> getReqParamMap() {
            return reqParamMap;
        }

        private void setReqParamMap(Map<String, String[]> reqParamMap) {
            this.reqParamMap = reqParamMap;
        }

        private HandlerMapper(Object controller, Method method, String regex) {
            this.controller = controller;
            this.method = method;
            this.regex = regex;
            paramIndexMapping = new HashMap<>();
            putParamIndexMapping(method);
        }

        private void putParamIndexMapping(Method method) {
            //提取方法中加了注解的参数
            Annotation[][] parameterAnnotations = method.getParameterAnnotations();
            paramCount = parameterAnnotations.length;
            for (int i = 0; i < parameterAnnotations.length; i++) {
                for (Annotation a : parameterAnnotations[i]) {
                    if (a instanceof RequestParameter) {
                        String paramName = ((RequestParameter) a).value();
                        if ("".equals(paramName)) {
                            paramIndexMapping.put(paramName, i);
                        }
                    }

                }
            }
            //提取方法中的request和response参数
            Class<?>[] paramTypes = method.getParameterTypes();
            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> paraType = paramTypes[i];
                if (paraType == HttpServletRequest.class || paraType == HttpServletResponse.class) {
                    paramIndexMapping.put(paraType.getName(), i);
                }
            }
        }

        private void setAttributeAndInvoke() {
            try {
                List<String> args = new ArrayList<>();
                for (Map.Entry<String, String[]> entry : reqParamMap.entrySet()) {
                    String[] value = entry.getValue();
                    if (value.length == 1) {
                        args.add(value[0]);
                    }
                }
                Object[] arrString = args.toArray();
                Object invoke = method.invoke(controller, arrString);
                System.out.println(invoke);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }
}
