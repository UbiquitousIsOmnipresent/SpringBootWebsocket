package com.Zhou.WebSocket;

import com.Zhou.UserEntity.Client;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

@ServerEndpoint(value = "/socketServer/{userName}")
@Component
public class SocketServer {

        public static int onlineNumber = 0;

        private static final Logger logger = LoggerFactory.getLogger(SocketServer.class);

        private Client myClient;
         //用线程安全的CopyOnWriteArraySet来存放客户端连接的信息
        private static CopyOnWriteArraySet<Client> socketServers = new CopyOnWriteArraySet<>();
        //一对一聊天 客户信息用ConcurrentHashMap
        private static Map<String, SocketServer> clients = new ConcurrentHashMap<>();

         // websocket封装的session,信息推送，就是通过它来信息推送
        private Session session;

        /**
         *
         * 服务端的userName,因为用的是set，每个客户端的username必须不一样，否则会被覆盖。
         * 要想完成ui界面聊天的功能，服务端也需要作为客户端来接收后台推送用户发送的信息
         */
        private final static String SYS_USERNAME = "zzygood1234";


        /**
         *
         * 用户连接时触发，我们将其添加到
         * 保存客户端连接信息的socketServers中
         *
         * @param session
         * @param userName
         */
        @OnOpen
        public void open(Session session,@PathParam(value="userName")String userName){

            this.session = session;
            socketServers.add(new Client(userName,session));

            logger.info("客户端:【{}】连接成功",userName);

            //messageType 1代表上线 2代表下线 3代表在线名单 4代表普通消息
            //先给所有人发送通知，说我上线了
            Map<String,Object> map1 = new HashMap<String,Object>();
            map1.put("messageType",1);
            map1.put("username",userName);


            //把自己的信息加入到map当中去
            clients.put(userName, this);
            //给自己发一条消息：告诉自己现在都有谁在线
            Map<String,Object> map2 = new HashMap<String,Object>();
            map2.put("messageType",3);
            //移除掉自己
            Set<String> set = clients.keySet();
            map2.put("onlineUsers",set);


        }

        /**
         *
         * 收到客户端发送信息时触发
         * 我们将其推送给客户端(zzygood1234)
         * 其实也就是服务端本身，为了达到前端聊天效果才这么做的
         *
         * @param message
         */
        @OnMessage
        public void onMessage(String message){

            Client client = socketServers.stream().filter( cli -> cli.getSession() == session)
                    .collect(Collectors.toList()).get(0);
            sendMessage(client.getUserName()+"<--"+message,SYS_USERNAME);

            logger.info("客户端:【{}】发送信息:{}",client.getUserName(),message);

            try {
                logger.info("来自客户端消息：" + message+"客户端的id是："+session.getId());
                JSONObject jsonObject = JSON.parseObject(message);
                String textMessage = jsonObject.getString("message");
                String fromusername = jsonObject.getString("username");
                String tousername = jsonObject.getString("to");
                //如果不是发给所有，那么就发给某一个人
                //messageType 1代表上线 2代表下线 3代表在线名单  4代表普通消息
                Map<String,Object> map1 =new HashMap<String,Object>();
                map1.put("messageType",4);
                map1.put("textMessage",textMessage);
                map1.put("fromusername",fromusername);
                if(tousername.equals("All")){
                    map1.put("tousername","所有人");
                    //sendMessageAll(JSON.toJSONString(map1),fromusername);
                }
                else{
                    map1.put("tousername",tousername);
                    //sendMessageTo(JSON.toJSONString(map1),tousername);
                }
            }
            catch (Exception e){
                logger.info("发生了错误了");
            }

        }

        @OnClose
        public void onClose(){
            onlineNumber--;
            socketServers.forEach(client ->{
                if (client.getSession().getId().equals(session.getId())) {

                    logger.info("客户端:【{}】断开连接",client.getUserName());
                    socketServers.remove(client);

                }
            });
            clients.remove(myClient.getUserName());
            try {
                //messageType 1代表上线 2代表下线 3代表在线名单  4代表普通消息
                Map<String,Object> map1 =new HashMap<String,Object>();
                map1.put("messageType",2);
                map1.put("onlineUsers",clients.keySet());
                map1.put("username",myClient.getUserName());
                //sendMessageAll(JSON.toJSONString(map1),username);
            }
            catch (Exception e){
                logger.info(myClient.getUserName()+"下线的时候通知所有人发生了错误");
            }
            logger.info("有连接关闭！ 当前在线人数" + onlineNumber);

        }


        @OnError
        public void onError(Throwable error) {
            socketServers.forEach(client ->{
                if (client.getSession().getId().equals(session.getId())) {
                    socketServers.remove(client);
                    logger.error("客户端:【{}】发生异常",client.getUserName());
                    error.printStackTrace();
                }
            });
        }

        /**
         *
         * 信息发送的方法，通过客户端的userName
         * 拿到其对应的session，调用信息推送的方法
         * @param message
         * @param userName
         */
        public synchronized static void sendMessage(String message,String userName) {

            socketServers.forEach(client ->{
                if (userName.equals(client.getUserName())) {
                    try {
                        client.getSession().getBasicRemote().sendText(message);

                        logger.info("服务端推送给客户端 :【{}】",client.getUserName(),message);

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        }

        /**
         *
         * 获取服务端当前客户端的连接数量，
         * 因为服务端本身也作为客户端接受信息，
         * 所以连接总数还要减去服务端
         * 本身的一个连接数
         *
         * 这里运用三元运算符是因为客户端第一次在加载的时候
         * 客户端本身也没有进行连接，-1 就会出现总数为-1的情况，
         * 这里主要就是为了避免出现连接数为-1的情况
         *
         * @return
         */
        public synchronized static int getOnlineNum(){
            return socketServers.stream().filter(client -> !client.getUserName().equals(SYS_USERNAME))
                    .collect(Collectors.toList()).size();
        }

        /**
         *
         * 获取在线用户名，前端界面需要用到
         * @return
         */
        public synchronized static List<String> getOnlineUsers(){

            List<String> onlineUsers = socketServers.stream()
                    .filter(client -> !client.getUserName().equals(SYS_USERNAME))
                    .map(client -> client.getUserName())
                    .collect(Collectors.toList());

            return onlineUsers;
        }

        /**
         *
         * 信息群发，我们要排除服务端自己不接收到推送信息
         * 所以我们在发送的时候将服务端排除掉
         * @param message
         */
        public synchronized static void sendAll(String message) {
            //群发，不能发送给服务端自己
            socketServers.stream().filter(cli -> cli.getUserName() != SYS_USERNAME)
                    .forEach(client -> {
                        try {
                            client.getSession().getBasicRemote().sendText(message);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });

            logger.info("服务端推送给所有客户端 :【{}】",message);
        }

        /**
         *
         * 多个人发送给指定的几个用户
         * @param message
         * @param persons
         */
        public synchronized static void SendMany(String message,String [] persons) {
            for (String userName : persons) {
                sendMessage(message,userName);
            }
        }
}
