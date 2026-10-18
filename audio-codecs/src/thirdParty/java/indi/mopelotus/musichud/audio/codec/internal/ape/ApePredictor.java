/* Controlled Java translation of FFmpeg n9.0.2 libavcodec/apedec.c.
 * Copyright (c) 2007 Benjamin Zores <ben@geexbox.org>, based on libdemac by Dave Chapman.
 * Java translation 2026. LGPL-2.1-or-later; see upstream/COPYING.LGPLv2.1.
 */
package indi.mopelotus.musichud.audio.codec.internal.ape;
import java.io.IOException;
import java.util.Arrays;
final class ApePredictor {
    static final int[][] ORDERS={{0,0,0},{16,0,0},{64,0,0},{32,256,0},{16,256,1280}};
    static final int[][] FRACBITS={{0,0,0},{11,0,0},{11,0,0},{10,13,0},{11,13,15}};
    static final int YDELAYA=50,YDELAYB=42,XDELAYA=34,XDELAYB=26;
    static final int YADAPTCOEFFSA=18,XADAPTCOEFFSA=14,YADAPTCOEFFSB=10,XADAPTCOEFFSB=5;
    static int APESIGN(int x){return x<0?1:x>0?-1:0;}
    final Filter[][] filters=new Filter[3][2];
    final int fset,version,bps;
    Predictor64 p=new Predictor64();
    int interimMode;
    ApePredictor(int compression,int version,int bps){
        fset=compression/1000-1;this.version=version;this.bps=bps;interimMode=bps==24?-1:0;
        for(int i=0;i<3&&ORDERS[fset][i]!=0;i++)for(int ch=0;ch<2;ch++)filters[i][ch]=new Filter(ORDERS[fset][i]);
    }
    static final class Predictor64 {
        long[] historybuffer=new long[562],lastA=new long[2],filterA=new long[2],filterB=new long[2];
        long[][] coeffsA={{360,317,-109,98},{360,317,-109,98}},coeffsB=new long[2][5];
        int buf;
        Predictor64 copy(){Predictor64 q=new Predictor64();q.historybuffer=historybuffer.clone();q.lastA=lastA.clone();q.filterA=filterA.clone();q.filterB=filterB.clone();for(int c=0;c<2;c++){q.coeffsA[c]=coeffsA[c].clone();q.coeffsB[c]=coeffsB[c].clone();}q.buf=buf;return q;}
        void advance(){if(++buf==512){System.arraycopy(historybuffer,buf,historybuffer,0,50);buf=0;}}
    }
    static final class Filter {
        final short[] coeffs,history;
        int delay,adapt;
        long avg;
        Filter(int order){coeffs=new short[order];history=new short[order*2+512];delay=order*2;adapt=order;}
    }
    // do_apply_filter + scalarproduct_and_madd_int16: int32 wrap and int16 coeff wrap are deliberate.
    void do_apply_filter(Filter f,int[] data,int count,int order,int fracbits) {
        for(int i=0;i<count;i++){
            int sum=0,sign=APESIGN(data[i]);
            for(int j=0;j<order;j++){
                sum+=f.coeffs[j]*f.history[f.delay-order+j];
                f.coeffs[j]=(short)(f.coeffs[j]+f.history[f.adapt-order+j]*sign);
            }
            int res=(int)(((long)sum+(1L<<(fracbits-1)))>>fracbits)+data[i];data[i]=res;
            f.history[f.delay++]=(short)Math.max(-32768,Math.min(32767,res));
            if(version<3980){f.history[f.adapt]=(short)(res==0?0:((res>>28)&8)-4);f.history[f.adapt-4]>>=1;f.history[f.adapt-8]>>=1;}
            else {
                long absres=res<0?-(long)res:res;
                f.history[f.adapt]=(short)(absres==0?0:APESIGN(res)*(8<<((absres>f.avg*3?1:0)+(absres>f.avg+f.avg/3?1:0))));
                f.avg=(f.avg+(int)(absres-f.avg)/16)&0xffffffffL;
                f.history[f.adapt-1]>>=1;f.history[f.adapt-2]>>=1;f.history[f.adapt-8]>>=1;
            }
            f.adapt++;
            if(f.delay==f.history.length){System.arraycopy(f.history,f.delay-order*2,f.history,0,order*2);f.delay=order*2;f.adapt=order;}
        }
    }
    void ape_apply_filters(int[][] data,int count,boolean mono) {
        for(int i=0;i<3&&ORDERS[fset][i]!=0;i++)for(int ch=0;ch<(mono?1:2);ch++)do_apply_filter(filters[i][ch],data[ch],count,ORDERS[fset][i],FRACBITS[fset][i]);
    }
    static int predictor_update_filter(Predictor64 p,int decoded,int filter,int delayA,int delayB,int adaptA,int adaptB,int interimMode) {
        long[] h=p.historybuffer;int b=p.buf;
        h[b+delayA]=p.lastA[filter];h[b+adaptA]=APESIGN((int)h[b+delayA]);
        h[b+delayA-1]=h[b+delayA]-h[b+delayA-1];h[b+adaptA-1]=APESIGN((int)h[b+delayA-1]);
        long predictionA=0,predictionB=0;
        for(int j=0;j<4;j++)predictionA+=h[b+delayA-j]*p.coeffsA[filter][j];
        h[b+delayB]=p.filterA[filter^1]-((p.filterB[filter]*31)>>5);h[b+adaptB]=APESIGN((int)h[b+delayB]);
        h[b+delayB-1]=h[b+delayB]-h[b+delayB-1];h[b+adaptB-1]=APESIGN((int)h[b+delayB-1]);p.filterB[filter]=p.filterA[filter^1];
        for(int j=0;j<5;j++)predictionB+=h[b+delayB-j]*p.coeffsB[filter][j];
        if(interimMode<1){predictionA=(int)predictionA;predictionB=(int)predictionB;p.lastA[filter]=(int)(decoded+((int)(predictionA+(predictionB>>1))>>10));}
        else p.lastA[filter]=decoded+((predictionA+(predictionB>>1))>>10);
        p.filterA[filter]=p.lastA[filter]+((p.filterA[filter]*31)>>5);
        int sign=APESIGN(decoded);
        for(int j=0;j<4;j++)p.coeffsA[filter][j]+=h[b+adaptA-j]*sign;
        for(int j=0;j<5;j++)p.coeffsB[filter][j]+=h[b+adaptB-j]*sign;
        return (int)p.filterA[filter];
    }
    void predictor_decode_stereo_3950(int[][] data,int count) {
        ape_apply_filters(data,count,false);
        Predictor64 interim=null;int[][] idata=null;int passes=1;
        if(interimMode==-1){interim=p.copy();idata=new int[][]{data[0].clone(),data[1].clone()};passes++;}
        for(int pass=0;pass<passes;pass++){
            Predictor64 cur=pass==0?p:interim;int[][] out=pass==0?data:idata;
            int mode=interimMode>0||pass!=0?1:0;cur.buf=0;
            for(int i=0;i<count;i++){
                int a0=predictor_update_filter(cur,out[0][i],0,YDELAYA,YDELAYB,YADAPTCOEFFSA,YADAPTCOEFFSB,mode);
                int a1=predictor_update_filter(cur,out[1][i],1,XDELAYA,XDELAYB,XADAPTCOEFFSA,XADAPTCOEFFSB,mode);
                out[0][i]=a0;out[1][i]=a1;
                if(passes>1){int left=a1-a0/2,right=left+a0;if(Math.abs((long)left)>(1<<23)||Math.abs((long)right)>(1<<23)){interimMode=1-mode;break;}}
                cur.advance();
            }
        }
        if(passes>1&&interimMode>0){System.arraycopy(idata[0],0,data[0],0,count);System.arraycopy(idata[1],0,data[1],0,count);p=interim;p.buf=0;}
    }
    void predictor_decode_mono_3950(int[][] data,int count) {
        ape_apply_filters(data,count,true);int currentA=(int)p.lastA[0];
        for(int i=0;i<count;i++){
            int a=data[0][i],b=p.buf;long[] h=p.historybuffer;
            h[b+YDELAYA]=currentA;h[b+YDELAYA-1]=h[b+YDELAYA]-h[b+YDELAYA-1];
            long prediction=0;for(int j=0;j<4;j++)prediction+=h[b+YDELAYA-j]*p.coeffsA[0][j];
            currentA=a+((int)prediction>>10);
            h[b+YADAPTCOEFFSA]=APESIGN((int)h[b+YDELAYA]);h[b+YADAPTCOEFFSA-1]=APESIGN((int)h[b+YDELAYA-1]);
            int sign=APESIGN(a);for(int j=0;j<4;j++)p.coeffsA[0][j]+=h[b+YADAPTCOEFFSA-j]*sign;
            p.advance();p.filterA[0]=currentA+((p.filterA[0]*31)>>5);data[0][i]=(int)p.filterA[0];
        }
        p.lastA[0]=currentA;
    }
}
