package com.collby.servlet;

import com.collby.annotation.*;
import com.sun.deploy.net.HttpRequest;
import com.sun.org.apache.xerces.internal.xs.StringList;
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
import java.lang.reflect.*;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;

public class MvcServlet extends HttpServlet {

    private Properties properties = new Properties();

    private String scanPackage = null;

    private List<String> classes = new ArrayList<>();

    private Map<String, Object> ioc = new HashMap<>();

    private List<HanderMapper> handerMapping = new ArrayList<>();

    @Override
    public void init(ServletConfig config) throws ServletException {
        System.out.println("hello spring_mvc");
        //初始化加载配置文件，通常是application.xml,这里用properties代替
        doLoadConfig(config.getInitParameter("contextConfigLocation"));

        //扫描所有的@Contorller @Service

//        doScanAnnotation(properties.getProperty("ScanPackage"));
        doScanAnnotation(scanPackage);
        //初始化这些类，并放在一个IOC容器
        doInitContainer();

        //进行依赖注入
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
        doDisptcher(req,resp);

    }


    private void doLoadConfig(String location) {
        if (location.contains("classpath")){
            if(location.contains("properties")){
                InputStream fis  = this.getClass().getClassLoader().getResourceAsStream(location.replaceAll("classpath:",""));
                try {
                    properties.load(fis);
                    if (fis != null) {
                        fis.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if(location.contains("xml")){
                InputStream fis  = this.getClass().getClassLoader().getResourceAsStream(location.replaceAll("classpath:",""));
                SAXReader reader = new SAXReader();
                Document doc = null;
                try {
                    doc = reader.read(fis);
                } catch (DocumentException e) {
                    e.printStackTrace();
                }
                if(doc !=null){
                    Element rootElement = doc.getRootElement();
                    Element locat = rootElement.element("PACKAGE");
                    scanPackage = locat.getText().trim();
                }

            }
        }

    }

    private void doScanAnnotation(String packageName) {
        URL url = this.getClass().getClassLoader().getResource("/" + packageName.replaceAll("\\.", "/"));
        if (url != null) {
            File resource = new File(url.getFile());
            File[] files =resource.listFiles();
            if(files != null){
                for (File file : files) {
                    if (file.isDirectory()){
                        doScanAnnotation(packageName+"."+file.getName());
                    }else {
                        String className = packageName + "." + file.getName().replaceAll(".class", "");
                        classes.add(className);
//                        System.out.println(className);
                    }
                }

            }
        }
    }

    private void doInitContainer() {
        if (classes.isEmpty()){return;}
        try {
            for (String className : classes) {
                Class<?> clazz = Class.forName(className);
                if (clazz.isAnnotationPresent(Controller.class)){
                    String beanName = lowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName,clazz.newInstance());

                }else if(clazz.isAnnotationPresent(Service.class)){
                    Object service = clazz.newInstance();//单例
                    Service s = clazz.getAnnotation(Service.class);
                    String beanName = s.value();
                    if(!"".equals(beanName.trim())){
                        ioc.put(beanName,service);
                    }else{
                        beanName = lowerFirstCase(clazz.getSimpleName());
                        ioc.put(beanName,service);
                    }

                    Class<?>[] interfaces = clazz.getInterfaces();
                    for (Class<?> itf:interfaces) {
                        beanName = lowerFirstCase(itf.getSimpleName());
                        ioc.put(beanName,service);
                    }
                }

            }
        }catch (Exception e){
            e.printStackTrace();
        }

    }

    private void doAutowired() {
        if(ioc.isEmpty()){return;}
        for (Map.Entry<String,Object> entry:ioc.entrySet()) {
//            System.out.println(entry.getKey());
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field: fields) {
                if(field.isAnnotationPresent(Autowired.class)){
                    Autowired autowired = field.getAnnotation(Autowired.class);
                    String beanName = autowired.value().trim();
                    if("".equals(beanName)){
                        beanName = field.getType().getSimpleName();
                    }
                    //暴力去私有化属性
                    field.setAccessible(true);
                    try {
                        field.set(entry.getValue(),ioc.get(lowerFirstCase(beanName)));
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void InitRequestMapping() {
        if(ioc.isEmpty()){return;}
        for (Map.Entry<String,Object> entry:ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            if(clazz.isAnnotationPresent(Controller.class)){
                String url ="";
                if(clazz.isAnnotationPresent(RequestMapping.class)){
                    RequestMapping requestMapping = clazz.getAnnotation(RequestMapping.class);
                    url = requestMapping.value();
                }

                Method[] methods = clazz.getMethods();
                for (Method method:methods) {
                    if(method.isAnnotationPresent(RequestMapping.class)){
                        RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
                        String regex = ("/"+ url + requestMapping.value()).replaceAll("/+","/");
                        handerMapping.add(new HanderMapper(entry.getValue(),method,regex));
//                        System.out.println("mapping" + regex +","+method);
                    }
                }

            }
        }
    }


    private void doDisptcher(HttpServletRequest req, HttpServletResponse resp) {

        String url = req.getRequestURI();
        Map<String, String[]> parameterMap = req.getParameterMap();
        Integer paramCount = parameterMap.size();
        for(HanderMapper handerMapper: handerMapping){
            if(Pattern.matches(handerMapper.regex,url)){
                System.out.println("匹配成功");
                if(handerMapper.paramCount.equals(paramCount)){

                    handerMapper.setReqParamMap(parameterMap);
                    handerMapper.setAttribute();
                    break;
                }
            }
        }
    }

    //首字母小写
    private String lowerFirstCase(String str){
        char[] chars = str.toCharArray();
        chars[0] +=32;
        return String.valueOf(chars);

    }
    private class HanderMapper{
        private Object controller;
        private Method method;
        private String  regex;
        private Integer paramCount;
        private Map<String,String[]> reqParamMap = null;
        private Map<String,Integer> paramIndexMapping = null; //参数顺序

        private Map<String, String[]> getReqParamMap() {
            return reqParamMap;
        }

        private void setReqParamMap(Map<String, String[]> reqParamMap) {
            this.reqParamMap = reqParamMap;
        }

        private HanderMapper(Object controller, Method method, String regex) {
            this.controller = controller;
            this.method = method;
            this.regex = regex;
            paramIndexMapping = new HashMap<>();
            putParamIndexMapping(method);
        }

        private void putParamIndexMapping(Method method){
            //提取方法中加了注解的参数
            Annotation[][] parameterAnnotations = method.getParameterAnnotations();
            paramCount = parameterAnnotations.length;
            for (int i = 0; i < parameterAnnotations.length; i++) {
                for (Annotation a: parameterAnnotations[i]) {
                    if(a instanceof RequestParameter){
                        String paramName = ((RequestParameter) a).value();
                        if("".equals(paramName)){
                            paramIndexMapping.put(paramName,i);
                        }
                    }
                    
                }
            }
            //提取方法中的request和response参数
            Class<?>[] paramTypes = method.getParameterTypes();
            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> paraType = paramTypes[i];
                if(paraType == HttpServletRequest.class || paraType ==HttpServletResponse.class){
                    paramIndexMapping.put(paraType.getName(), i);
                }
            }
        }

        private void setAttribute() {
            try {
               List<String> args = new ArrayList<String>();
                for (Map.Entry entry:reqParamMap.entrySet()){
                    String[] value = (String[]) entry.getValue();
                    if(value.length == 1){
                        args.add(value[0]);
                    }
                }
                Object[] arrString = args.toArray();
                Object invoke = method.invoke(controller,arrString);
                System.out.println(invoke);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }
}
