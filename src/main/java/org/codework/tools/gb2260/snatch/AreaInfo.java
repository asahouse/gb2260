package org.codework.tools.gb2260.snatch;

import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AreaInfo {
  String path;
  String code;
  String shortCode;
  String name;

  String parentCode;

  String provinceCode;
  String cityCode;
  String countyCode;
  String townCode;
  String villageCode;
  String categoryCode;

  boolean isDirectlyCity;
  boolean isDirectlyCounty;
  String time;

  public String getProvinceCode() {
    if (this.getCurrentLayer().toValue()>=AreaLayer.Province.toValue())
      return AreaSnatchHelper.getFullCodeByShortCode(this.code.substring(0,2));
    else return "0";
  }

  public String getCityCode() {
    if (this.getCurrentLayer().toValue()>=AreaLayer.City.toValue())
      return AreaSnatchHelper.getFullCodeByShortCode(this.code.substring(0,4));
    else return "0";
  }

  public String getCountyCode() {
    if (this.getCurrentLayer().toValue()>=AreaLayer.County.toValue())
      return AreaSnatchHelper.getFullCodeByShortCode(this.code.substring(0,6));
    else return "0";
  }

  public String getTownCode() {
    if (this.getCurrentLayer().toValue()>=AreaLayer.Town.toValue())
      return AreaSnatchHelper.getFullCodeByShortCode(this.code.substring(0,8));
    else return "0";
  }

  public String getVillageCode() {
    if (this.getCurrentLayer().toValue()>=AreaLayer.Village.toValue())
      return AreaSnatchHelper.getFullCodeByShortCode(this.code.substring(0,10));
    else return "0";
  }

  /**
   * 可从编码上判断市直辖街道
   * @return
   */
  public boolean isDirectlyCounty() {
    return Integer.valueOf(this.code.substring(4,6))==0
            && Integer.valueOf(this.code.substring(6,9))>0;
  }

  public AreaLayer getCurrentLayer() {
    Integer zeroBit = this.zeroBit(this.code);
    if (zeroBit>=0 && zeroBit<3) return AreaLayer.Village;
    else if (zeroBit>=3 && zeroBit<6) return AreaLayer.Town;
    else if (zeroBit>=6 && zeroBit<8) return AreaLayer.County;
    else if (zeroBit>=8 && zeroBit<10) return AreaLayer.City;
    else if (zeroBit>=10) return AreaLayer.Province;
    else return AreaLayer.None;
  }

  public String toJson(){
    return JSONObject.toJSON(this).toString();
  }

  private Integer zeroBit(String intVal){
    BigDecimal d = new BigDecimal(10);
    boolean existPoint = true;
    BigDecimal b = new BigDecimal(intVal);
    int count = 0;
    while(existPoint){
      BigDecimal r = b.divide(d);
      existPoint = r.toString().indexOf(".")==-1;
      b = r;
      //得到小数不计算
      if (existPoint) {count++;}
    }
    return count;
  }

  public static void main(String[] args) {
    String tmp = "442000001007";
    boolean i = Integer.valueOf(tmp.substring(4,6))==0
            && Integer.valueOf(tmp.substring(6,9))>0;

    //Integer count = AreaInfo.zeroBit(tmp);
    System.out.println(i);
  }
}
