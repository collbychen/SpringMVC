package com.collby.service;

import com.collby.annotation.Service;

@Service
public class LoginServiceImp implements LoginService{

    @Override
    public String login(String u,String p) {

        return "mvc就是这么简单,登录账号为"+ u +";密码为"+ p + ";";
    }
}
