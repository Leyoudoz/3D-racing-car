// 纯 Java 物理仿真测试：验证 VehiclePhysics + AI 循迹不会冲出赛道。
// 复制核心逻辑到独立文件，用 javac 编译运行（无 Android 依赖）。
public class PhysicsSim {
    static final int N=96, CAR_COUNT=6;
    static float[] cx=new float[N],cy=new float[N],cz=new float[N],widths=new float[N];
    static float baseR=111.7f; // 近似赛道
    static {
        for(int i=0;i<N;i++){float a=2*(float)Math.PI*i/N;
            float rad=baseR*(1+0.16f*(float)Math.sin(3*a+1.2)+0.08f*(float)Math.cos(7*a+0.5));
            cx[i]=(float)Math.cos(a)*rad; cz[i]=(float)Math.sin(a)*rad;
            cy[i]=3*(float)Math.sin(a+0.6)+1.5f*(float)Math.sin(3*a+2);
            widths[i]=8f;
        }
    }
    static float[] pointAt(float t){int i=((int)Math.floor(t))%N;if(i<0)i+=N;int j=(i+1)%N;float f=t-(float)Math.floor(t);
        return new float[]{cx[i]+(cx[j]-cx[i])*f,cy[i]+(cy[j]-cy[i])*f,cz[i]+(cz[j]-cz[i])*f,widths[i]+(widths[j]-widths[i])*f};}
    static float yawAt(float t){float[] a=pointAt(t),b=pointAt(t+0.5f);return (float)Math.atan2(b[0]-a[0],b[2]-a[2]);}
    static float wrap(float a){while(a>Math.PI)a-=2*(float)Math.PI;while(a<-Math.PI)a+=2*(float)Math.PI;return a;}

    static class Car{
        float x,z,yaw,trackT,speed,vy,yawRate,steerAngle;
        float aiSkill;
        float maxSpeed=68;
        void reset(int g){trackT=-(g*1.5f)-2f;float[] p=pointAt(trackT);yaw=yawAt(trackT);
            float nx=(float)Math.cos(yaw),nz=-(float)Math.sin(yaw);float side=g%2==0?-1.8f:1.8f;
            x=p[0]+nx*side;z=p[2]+nz*side;speed=vy=yawRate=steerAngle=0;}
        void step(float dt){
            float look=3.0f+speed*0.06f;
            float[] target=pointAt(trackT+look);
            float desired=(float)Math.atan2(target[0]-x,target[2]-z);
            float headingErr=wrap(desired-yaw);
            float[] cp=pointAt(trackT);float tYaw=yawAt(trackT);
            float trx=(float)Math.cos(tYaw),trz=-(float)Math.sin(tYaw);
            float lateralErr=(x-cp[0])*trx+(z-cp[2])*trz;
            float cross=(float)Math.atan(-1.5f*lateralErr/(speed+6f));
            float steerIn=clamp(-(headingErr+cross)*1.6f,-1,1);
            // 前瞻距离随速度增加：刹车距离约 v²/(2*decel)，decel≈8m/s²
            float lookAhead=5+speed*speed/16f;
            float curve=0;
            for(float dt2=0;dt2<lookAhead;dt2+=2){
                curve=Math.max(curve,Math.abs(wrap(yawAt(trackT+dt2+2)-yawAt(trackT+dt2))));
            }
            // 由曲率算安全速度：a_lat_max≈8m/s², v=sqrt(a/curvature_per_meter)
            float curvPerM=curve/2f;
            float safeSpeed=(float)Math.sqrt(8f/Math.max(0.005f,curvPerM));
            float targetSpeed=Math.min(maxSpeed*aiSkill, safeSpeed);
            targetSpeed=Math.min(targetSpeed,26+aiSkill*10);
            float throttle=speed<targetSpeed?0.75f:0, brake=speed>targetSpeed+2?0.6f:0;
            // physics
            float sf=1f/(1f+0.0022f*speed*speed);
            float maxDelta=(float)Math.toRadians(34)*Math.max(0.18f,sf);
            float targetDelta=-steerIn*maxDelta;
            steerAngle+=(targetDelta-steerAngle)*Math.min(1,dt*8);
            float driveF=throttle*12000*(1-0.55f*Math.min(1,speed/78));
            float brakeF=brake*16000;
            float airF=0.5f*1.225f*0.38f*2.1f*speed*speed;
            float fx=driveF-brakeF-(throttle<0.05f?350:0)-airF-0.015f*1480*9.81f;
            if(fx<0&&speed<0.5f){fx=0;speed=0;}
            float v=Math.max(2,speed);
            float beta=(speed>0.5f)?(float)Math.atan2(vy,v):0;
            float alphaF=clamp(steerAngle-beta-(1.25f*yawRate)/v,-0.6f,0.6f);
            float alphaR=clamp(-beta+(1.4f*yawRate)/v,-0.6f,0.6f);
            float fyf=2*pacejka(alphaF,10,1.9f,1.05f,-0.5f,3600);
            float fyr=2*pacejka(alphaR,11,1.9f,1.10f,-0.5f,3600);
            float ay=(fyf*(float)Math.cos(steerAngle)+fyr)/1480-v*yawRate;
            vy+=ay*dt;
            float yawAcc=(1.25f*fyf*(float)Math.cos(steerAngle)-1.4f*fyr)/2100;
            yawRate+=yawAcc*dt;yawRate*=1-0.3f*dt;
            speed=Math.max(0,Math.min(speed+fx/1480*dt,82));
            // integrate world
            yaw+=yawRate*dt;
            float fdx=(float)Math.sin(yaw),fdz=(float)Math.cos(yaw);
            float rdx=-(float)Math.cos(yaw),rdz=(float)Math.sin(yaw);
            x+=(fdx*speed+rdx*vy)*dt;z+=(fdz*speed+rdz*vy)*dt;
            // track projection
            float best=trackT,bd=1e9f;
            for(int d=-3;d<=10;d++){float t=trackT+d;float[] p=pointAt(t);float dd=(p[0]-x)*(p[0]-x)+(p[2]-z)*(p[2]-z);if(dd<bd){bd=dd;best=t;}}
            trackT=best;
        }
        float off(){float[] p=pointAt(trackT);return (float)Math.hypot(x-p[0],z-p[2]);}
    }
    static float pacejka(float a,float B,float C,float D,float E,float fz){float Ba=B*a;return D*fz*(float)Math.sin(C*Math.atan(Ba-E*(Ba-Math.atan(Ba))));}
    static float clamp(float v,float lo,float hi){return v<lo?lo:(v>hi?hi:v);}

    public static void main(String[] args){
        Car[] cars=new Car[CAR_COUNT];
        for(int i=0;i<CAR_COUNT;i++){cars[i]=new Car();cars[i].aiSkill=0.83f+i*0.03f;cars[i].reset(i);}
        float[] maxOff=new float[CAR_COUNT];
        float dt=1f/60;
        for(int step=0;step<60*60;step++){ // 60秒
            for(int i=0;i<CAR_COUNT;i++){cars[i].step(dt);maxOff[i]=Math.max(maxOff[i],cars[i].off());}
        }
        System.out.println("=== AI 循迹测试（60秒）===");
        for(int i=0;i<CAR_COUNT;i++)System.out.printf("car%d skill=%.2f maxOff=%.2fm trackHalfWidth=8m %s%n",
            i,cars[i].aiSkill,maxOff[i],maxOff[i]<8?"OK":"OFF_TRACK");
    }
}
