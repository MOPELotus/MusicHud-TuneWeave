/* Controlled Java translation of FFmpeg n9.0.2 libavcodec/apedec.c.
 * Copyright (c) 2007 Benjamin Zores <ben@geexbox.org>, based on libdemac by Dave Chapman.
 * Java translation 2026. LGPL-2.1-or-later; see upstream/COPYING.LGPLv2.1.
 * Function names and branch boundaries deliberately follow pinned C source.
 */
package indi.mopelotus.musichud.audio.codec.internal.ape;
import java.io.IOException;
final class ApeEntropy {
    static final int[] COUNTS_3980={0,19578,36160,48417,56323,60899,63265,64435,64971,65232,65351,65416,65447,65466,65476,65482,65485,65488,65490,65491,65492,65493};
    static final int[] DIFF_3980={19578,16582,12257,7906,4576,2366,1170,536,261,119,65,31,19,10,6,3,3,2,1,1,1};
    static final int[] COUNTS_3970={0,14824,28224,39348,47855,53994,58171,60926,62682,63786,64463,64878,65126,65276,65365,65419,65450,65469,65480,65487,65491,65493};
    static final int[] DIFF_3970={14824,13400,11124,8507,6139,4177,2755,1756,1104,677,415,248,150,89,54,31,19,11,7,4,2};
    static final long U32=0xffffffffL;
    static final class Rice { int k=10,ksum=16384; }
    final byte[] data;
    final int version;
    int pos,buffer,flags;
    long low,range,help,crc;
    final Rice riceX=new Rice(),riceY=new Rice();
    ApeEntropy(byte[] data,int offset,int version) throws IOException {
        this.data=data; this.pos=offset; this.version=version;
        crc=readBe32();
        if((crc&0x80000000L)!=0){ crc&=0x7fffffffL; flags=(int)readBe32(); }
        if((flags&~7)!=0)throw new IOException("Unsupported APE frame flags: "+flags);
        readByte(); // first eight bits ignored, as init_entropy_decoder
        buffer=readByte(); low=buffer>>>1; range=128;
    }
    int readByte() throws IOException { if(pos>=data.length)throw new IOException("Truncated APE entropy stream"); return data[pos++]&255; }
    long readBe32() throws IOException {return ((long)readByte()<<24)|((long)readByte()<<16)|(readByte()<<8)|readByte();}
    void range_dec_normalize() throws IOException {
        while(range<=0x800000L){buffer=(buffer<<8)|readByte();low=((low<<8)|((buffer>>>1)&255))&U32;range=(range<<8)&U32;}
    }
    int range_decode_culfreq(int total) throws IOException {
        if(total<=0)throw new IOException("Invalid APE frequency total");
        range_dec_normalize();help=range/total;
        if(help==0)throw new IOException("APE zero range");
        long value=low/help;if(value>Integer.MAX_VALUE)throw new IOException("APE frequency overflow");return (int)value;
    }
    int range_decode_culshift(int shift) throws IOException {
        if(shift<0||shift>31)throw new IOException("Invalid APE shift");
        range_dec_normalize();help=range>>>shift;
        if(help==0)throw new IOException("APE zero shifted range");
        long value=low/help;if(value>Integer.MAX_VALUE)throw new IOException("APE shifted frequency overflow");return (int)value;
    }
    void range_decode_update(int width,int start){low=(low-help*start)&U32;range=(help*width)&U32;}
    int range_decode_bits(int n) throws IOException {int sym=range_decode_culshift(n);range_decode_update(1,sym);return sym;}
    int range_get_symbol(int[] counts,int[] diff) throws IOException {
        int cf=range_decode_culshift(16);
        if(cf>65535)throw new IOException("Invalid APE range symbol");
        if(cf>65492){range_decode_update(1,cf);return cf-65535+63;}
        int symbol=0;while(counts[symbol+1]<=cf)symbol++;
        range_decode_update(diff[symbol],counts[symbol]);return symbol;
    }
    static void update_rice(Rice rice,int x) throws IOException {
        int lim=rice.k!=0?1<<(rice.k+4):0;
        rice.ksum += ((x+1)>>>1)-((rice.ksum+16)>>>5);
        if(Integer.compareUnsigned(rice.ksum,lim)<0)rice.k--;
        else if(Integer.compareUnsigned(rice.ksum,1<<(rice.k+5))>=0&&rice.k<24)rice.k++;
        if(rice.k<0||rice.k>24)throw new IOException("Invalid APE rice state");
    }
    int ape_decode_value_3990(Rice rice) throws IOException {
        int pivot=Math.max(rice.ksum>>>5,1);
        int overflow=range_get_symbol(COUNTS_3980,DIFF_3980);
        if(overflow==63)overflow=(range_decode_bits(16)<<16)|range_decode_bits(16);
        int base;
        if(pivot<0x10000){base=range_decode_culfreq(pivot);range_decode_update(1,base);}
        else {
            int baseHi=pivot,bits=0;while((baseHi&~0xffff)!=0){baseHi>>>=1;bits++;}
            baseHi=range_decode_culfreq(baseHi+1);range_decode_update(1,baseHi);
            int baseLo=range_decode_culfreq(1<<bits);range_decode_update(1,baseLo);base=(baseHi<<bits)+baseLo;
        }
        int x=base+overflow*pivot;update_rice(rice,x);return ((x>>>1)^((x&1)-1))+1;
    }
    int ape_decode_value_3900(Rice rice) throws IOException {
        int overflow=range_get_symbol(COUNTS_3970,DIFF_3970),k;
        if(overflow==63){k=range_decode_bits(5);overflow=0;}else k=Math.max(rice.k-1,0);
        int x;
        if(k<=16){x=range_decode_bits(k);}else if(k<=31){x=range_decode_bits(16)|(range_decode_bits(k-16)<<16);}else throw new IOException("Invalid APE rice bit count");
        x+=overflow<<k;update_rice(rice,x);return ((x>>>1)^((x&1)-1))+1;
    }
    void entropy_decode(int[][] decoded,int count,boolean mono) throws IOException {
        for(int i=0;i<count;i++) {
            decoded[0][i]=version>=3990?ape_decode_value_3990(riceY):ape_decode_value_3900(riceY);
            if(!mono)decoded[1][i]=version>=3990?ape_decode_value_3990(riceX):ape_decode_value_3900(riceX);
        }
    }
}
