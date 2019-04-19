package com.collby.controller;


import com.collby.annotation.Autowired;
import com.collby.annotation.Controller;
import com.collby.annotation.RequestMapping;
import com.collby.annotation.RequestParameter;
import com.collby.service.LoginService;

@Controller
@RequestMapping(value = "/collby")
public class LoginController {

    @Autowired
    private LoginService loginService;

    @RequestMapping(value = "/login")
    public String login(@RequestParameter("username") String username,@RequestParameter("password") String password){
        System.out.println("调用了方法");
        if(loginService != null){
            return loginService.login(username,password);
        }
        return "ok";
    }

    @RequestMapping(value = "/logout")
    public void logout(){
    }
}
