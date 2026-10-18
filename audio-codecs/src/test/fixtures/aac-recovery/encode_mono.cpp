// SPDX-License-Identifier: CC0-1.0
// Self-generated audio fixture encoder driver. FDK is used only during generation.
#include "aacenc_lib.h"
#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
#include <stdexcept>
static void be32(std::ostream& o,uint32_t n){for(int i=3;i>=0;i--)o.put((n>>(i*8))&255);}
int main(int argc,char**argv){
 try {
 if(argc!=7)throw std::runtime_error("usage: encode_fixture PCM_S16LE BUNDLE AOT PERIOD SIGNALING BITRATE");
 const int aot=std::stoi(argv[3]),period=std::stoi(argv[4]),sig=std::stoi(argv[5]),br=std::stoi(argv[6]);
 std::ifstream in(argv[1],std::ios::binary);if(!in)throw std::runtime_error("open PCM");std::vector<char>b((std::istreambuf_iterator<char>(in)),{});if(b.size()%2)throw std::runtime_error("unaligned mono PCM");
 std::vector<INT_PCM>pcm(b.size()/2);for(size_t i=0;i<pcm.size();i++)pcm[i]=(int16_t)((uint8_t)b[2*i]|((uint8_t)b[2*i+1]<<8));
 HANDLE_AACENCODER h=nullptr;if(aacEncOpen(&h,0,1)!=AACENC_OK)throw std::runtime_error("open AAC encoder");
 auto set=[&](AACENC_PARAM p,UINT v){int e=aacEncoder_SetParam(h,p,v);if(e)throw std::runtime_error("set param "+std::to_string(p)+" error "+std::to_string(e));};
 set(AACENC_AOT,aot);set(AACENC_SAMPLERATE,48000);set(AACENC_CHANNELMODE,MODE_1);set(AACENC_CHANNELORDER,1);set(AACENC_BITRATE,br);set(AACENC_TRANSMUX,0);set(AACENC_SIGNALING_MODE,sig);set(AACENC_HEADER_PERIOD,period);set(AACENC_AFTERBURNER,1);
 if(aacEncEncode(h,nullptr,nullptr,nullptr,nullptr)!=AACENC_OK)throw std::runtime_error("init encode");
 AACENC_InfoStruct info={};if(aacEncInfo(h,&info)!=AACENC_OK)throw std::runtime_error("get info");
 std::vector<std::vector<uint8_t>> packets;size_t offset=0;
 for(int guard=0;guard<10000;guard++){
  uint8_t outbuf[16384];void*ob=outbuf;INT oid=OUT_BITSTREAM_DATA,osize=sizeof outbuf,oelement=1;AACENC_BufDesc od={1,&ob,&oid,&osize,&oelement};
  AACENC_InArgs ia={};AACENC_OutArgs oa={};AACENC_BufDesc id={};void*ib=nullptr;INT iid=IN_AUDIO_DATA,isize=0,ielement=sizeof(INT_PCM);
  if(offset<pcm.size()){size_t n=std::min(pcm.size()-offset,(size_t)info.frameLength);ib=pcm.data()+offset;isize=n*sizeof(INT_PCM);id={1,&ib,&iid,&isize,&ielement};ia.numInSamples=n;}else ia.numInSamples=-1;
  auto e=aacEncEncode(h,&id,&od,&ia,&oa);if(e==AACENC_ENCODE_EOF)break;if(e!=AACENC_OK)throw std::runtime_error("encode "+std::to_string(e));offset+=oa.numInSamples;
  if(oa.numOutBytes>0)packets.emplace_back(outbuf,outbuf+oa.numOutBytes);
  if(guard==9999)throw std::runtime_error("encoder stalled");
 }
 std::ofstream out(argv[2],std::ios::binary);be32(out,info.confSize);out.write((const char*)info.confBuf,info.confSize);be32(out,packets.size());for(auto&p:packets){be32(out,p.size());out.write((const char*)p.data(),p.size());}out.close();aacEncClose(&h);
 std::cout<<"{\"aot\":"<<aot<<",\"sample_rate\":48000,\"channels\":1,\"requested_header_period\":"<<period<<",\"signaling\":"<<sig<<",\"bitrate\":"<<br<<",\"frame_length\":"<<info.frameLength<<",\"encoder_delay\":"<<info.nDelay<<",\"packet_count\":"<<packets.size()<<",\"asc_hex\":\"";const char*hex="0123456789abcdef";for(UINT i=0;i<info.confSize;i++)std::cout<<hex[info.confBuf[i]>>4]<<hex[info.confBuf[i]&15];std::cout<<"\"}\n";
 }catch(const std::exception&e){std::cerr<<e.what()<<"\n";return 1;}
}
