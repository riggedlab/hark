# Hark DT-1 自制 PCB v1：选型与硬件调研

查询日期：**2026-11-10**。范围：前期器件与工艺调研笔记（尚未画板打样）。库存与价格来自国内立创商城和嘉立创 SMT 页面；规格优先采用原厂资料。下文“估算”“设计建议”“未确认”均不代表实测或工厂报价。

## 1. 推荐方案与结论

**推荐把 HJ-N54_SIP-L15 模块作为有条件的首选，搭配 MSM261DGT003 顶进音 PDM 麦克风、BQ25185 充电管理、TPS62840 的 1.8V 降压供电；四层 0.8mm、单面贴装，先贴两片。** HJ 模块在国内 SMT 库实查 **38 个、¥30.45/个**，尺寸 **4.5×4.5×1.0mm**。它比 QFN48 芯片本体还小，当前没有为省面积改裸片的理由。但具体货号的开放烧录配置、32k 晶振标配与经济型 LGA 可贴性尚需确认，不能直接定板。以约 **30×25mm PCB** 作为第一轮摆件起点较稳妥（工程建议，尚未布局，不是已证明尺寸）；当前连接器直接叠电池的计算厚度为 **302020：9.9mm；402020：10.9mm**，还没计电池鼓胀和整机公差。把高连接器移到电池旁边才有机会更薄，但会增大平面面积。两片 PCBA 暂留 **约 ¥450–650** 预算，不含电池、外壳与调试工具；这是按国内收费公式和料价计算的预算，不是已生成的订单报价。来源与计算见下文。

现在不能承诺“25×23mm、明显薄于10mm、全天续航”同时成立。三个主要约束是：

1. **平均功耗未测。** 若 5–10mA 指电池端，100mAh 理想续航只有 10–20h；如果是 1.8V 系统端，必须换算功率，不能直接除电池容量。
2. **连接器比模块高。** 查到的 MX1.25 卧贴座是 3.6mm 高；带保护的“302020”规格示例实际长22mm，不是20mm。
3. **天线仍需整机验证。** 超小模块内置天线不意味着可以压在电池下面；厂家未给出可直接照抄的电池净空毫米数。

相比初版设想，建议明确调整两处：拨动开关直接控制 Buck 的 EN（使能脚，决定稳压器开关），省掉独立主电源负载开关；麦克风改为有现货、有完整手册的 DGT003，不能假设和原 DGT006 是直接换封装的同一器件。是否接受这些取舍见第11节。

## 2. 候选 BOM 与实际库存

**这是一份选型 BOM，不是可直接上传生产的完整 BOM。** 电阻、电容、LED、ADC隔离开关、温度检测网络尚未做原理图级选型；没有凭空填编号。商城库存与 SMT 库存分开记录，不能拿“商城可以买”代替“经济型可贴”。表中库存带 K+ 的沿用网页显示精度。

| 用途/地位 | 型号、立创编号 | 国内现货：商城 / SMT | 查询单价与阶梯 | 封装、尺寸/高度 | 资料 |
|---|---|---|---|---|---|
| 主控首选，有条件 | HJ-N54_SIP-L15；C54021748 | 38 / **38（SMT详情核实）** | SMT 1+ ¥30.45 | LGA45；4.5×4.5mm；1.0mm典型、1.05mm最大 | [商城](https://item.szlcsc.com/57236615.html)、[SMT](https://www.jlc-smt.com/lcsc/detail/C54021748.html)、[厂家手册](https://atta.szlcsc.com/upload/public/pdf/source/20261009/5C637C454D23CBC0ADC1CBC2D6490186.pdf) |
| 模块备选，缺货 | Raytac AN54LQ-15；C49419243 | 0 / 0 | 商城1+ ¥31.13；不是现货报价承诺 | 底部43焊盘；13.7×9.5×1.8mm，厚度公差最高+0.20mm | [商城](https://item.szlcsc.com/51943024.html)、[手册](https://atta.szlcsc.com/upload/public/pdf/source/20260417/196CB29ADDF9F5C5D77AA7083ED7C2B7.pdf) |
| 裸片后备，缺货 | NRF54L15-QFAA-R；C42458750 | 0 / 0 | 商城1+ ¥25.23；SMT预订参考¥16.06不是两片成交价 | QFN48，6×6mm；0.85mm标称、0.90mm最大 | [商城](https://item.szlcsc.com/44536435.html)、[SMT](https://www.jlc-smt.com/lcsc/detail/C42458750.html)、[Nordic规格书](https://files.seeedstudio.com/wiki/XIAO_nRF54L15/Getting_Start/Nordic_nRF54L15_Datasheet_v1.0.pdf) |
| 推荐麦克风 | MSM261DGT003；C48227730 | 1,649，另有在途5,700 / **2,311（详情核实）** | SMT 1+ ¥5.47 | LGA6，4×2×1.1mm；高度±0.1mm | [商城](https://item.szlcsc.com/50474176.html)、[手册](https://atta.szlcsc.com/upload/public/pdf/source/20250417/FFCE6EA3A1F270F7DA3F96571552317A.pdf) |
| 推荐充电 | BQ25185DLHR；C19725033 | 1,835 / **2,195（详情核实）** | SMT 1+ ¥9.48 | WSON10，2.2×2mm，最高0.8mm | [商城](https://item.szlcsc.com/21048130.html)、[TI](https://www.ti.com/lit/ds/symlink/bq25185.pdf) |
| 推荐稳压 | TPS62840DLCR；C2071859 | 1,977 / **2,387（详情核实）** | SMT 1+ ¥23.42 | VSON8，1.5×2mm；最大高度按采购封装图复核，未确认 | [商城](https://item.szlcsc.com/2163442.html)、[TI](https://www.ti.com/lit/ds/symlink/tps62840.pdf) |
| Buck配套电感 | DFE201612E-2R2M=P2；C337893 | 7,670 / 商城显示SMT 31K+ | 商城5+ ¥0.9521；SMT两片价未确认 | 2.2µH，2016；2×1.6×1.2mm最大 | [商城](https://item.szlcsc.com/315673.html)、[TI推荐用料表](https://www.ti.com/lit/ds/symlink/tps62840.pdf) |
| 拨动开关 | SHOU HAN MSK12C02；C431540 | 165K+ / **178,370（详情核实）** | SMT1+ ¥0.316；商城10+ ¥0.31578 | 8×2.8mm；壳高1.4±0.1mm；侧向柄与定位柱另占空间 | [商城](https://item.szlcsc.com/427897.html)、[图纸](https://atta.szlcsc.com/upload/public/pdf/source/20260709/0B6CA1F2CE6409C924A760E5E0AA1320.pdf) |
| 电池插座候选 | XUNPU WAFER-MX1.25-2PWB；C3029359 | 46K+ / 商城显示SMT 46K+ | 商城10+ ¥0.28956；SMT两片价未确认 | 卧贴；宽7.5mm、前后外廓约5.25mm；高3.6±0.1mm | [商城](https://item.szlcsc.com/3561371.html)、[图纸](https://atta.szlcsc.com/upload/public/pdf/source/20220527/5ACBB683E384B6AD06743F272F933620.pdf) |
| USB-C候选 | HRO TYPE-C-31-M-12；C165948 | 276K+ / 商城显示SMT 294K+ | 商城5+ ¥1.051008；SMT两片价未确认 | 16P，卧贴；图纸板上高度约3.26mm，含固定脚总高度4.70mm | [商城](https://item.szlcsc.com/177331.html)、[图纸](https://atta.szlcsc.com/upload/public/pdf/source/20220525/466408E853115E789EDB0DDC29077C05.pdf) |
| CC静电保护候选 | PESD5V0S1UL,315；C85402 | 355K+ / 商城显示SMT 355K+ | 商城20+ ¥0.1402 | DFN1006-2；1×0.6mm；高度未确认 | [商城](https://item.szlcsc.com/86575.html)、[Nexperia](https://assets.nexperia.com/documents/data-sheet/PESD5V0S1UL.pdf) |
| LDO备选 | TPS7A0218PDBVR；C3748843 | 3,015 / 商城显示SMT 3,224 | 商城1+ ¥3.92 | SOT23-5，最大高度未确认 | [商城](https://item.szlcsc.com/4316370.html)、[TI](https://www.ti.com/lit/ds/symlink/tps7a02.pdf) |
| 充电备选 | MCP73831T-2ACI/OT；C424093 | 11K+ / 商城显示SMT 11K+ | 商城1+ ¥4.83 | SOT23-5，最大高度未确认 | [商城](https://item.szlcsc.com/417116.html)、[Microchip](https://www.microchip.com/content/dam/mchp/documents/APID/ProductDocuments/DataSheets/MCP73831-Family-Data-Sheet-DS20001984H.pdf) |
| 国产充电比较项 | TPOWER TP4057；C440390 | 商城未独立核 / **SMT5,059（详情核实）** | SMT1+ ¥0.436 | SOT23-6，高度未确认 | [SMT](https://www.jlc-smt.com/lcsc/detail/C440390.html)、[厂家手册](https://datasheet.lcsc.com/datasheet/pdf/093610ce9300ede501b5eb2790fadaea.pdf?productCode=C440390) |
| 不推荐照搬的负载开关 | TPS22916BYFPR；C2150095 | 12K+ / 商城显示SMT 12K+ | 商城5+ ¥1.0732 | DSBGA4，0.7×0.7mm；高度及经济型兼容未确认 | [商城](https://item.szlcsc.com/2242142.html)、[TI](https://www.ti.com/lit/ds/symlink/tps22916.pdf) |
| 待原理图定值/封装 | CC 5.1kΩ×2、ISET、VSET、分压与控制电阻、去耦/储能电容、LED及限流、ADC隔离、温度检测、VBUS TVS | 未确认 | 预算预留，不是已选定料价 | 优先0402/0603；不得把未选型器件当作已验证厚度 | 依上列芯片参考电路补齐 |

上列主体器件均在页面标为扩展库。是否享免换料费活动，以最终上传BOM时系统为准。后续核对插座SMT详情时出现滑动验证，未继续该访问；此前商城显示的SMT数量已保留，但没有冒充详情复核。没有操作购物车、生成订单或使用个人资料。

## 3. 主控模块、引脚和裸片取舍

### 模块比较

| 项目 | HJ-N54_SIP-L15 | AN54LQ-15 |
|---|---|---|
| IO | 最多32个；使用低频晶振时P1.00/P1.01不能当普通IO | 最多31个；应扣除选用功能占脚 |
| 高频晶振 | 内部电路图含32MHz | 已内置32MHz |
| 低频晶振 | **资料冲突**：引脚表说内置32.768kHz，图5.1标Optional；C54021748实物配置未确认 | 不内置；可外接32.768kHz或用内部RC |
| DC/DC电感 | 内部图含4.7µH | 需要外置4.7µH；DECD 2.2µF电容已内置 |
| 天线 | 内置；RF脚23需连接天线脚24，建议留π匹配位 | 芯片天线；厂家图标9.5×3.3mm No Ground-Pad区 |
| 净空 | 天线附近/背面不放件、不走线，避铜、靠板角；具体毫米距离未确认 | 天线区放板边；9.5×3.3mm是禁地焊盘区，**不是电池允许靠近距离** |
| 认证 | 手册明确RoHS；SRRC/FCC等证书未确认 | 手册有SRRC证书CMIIT ID 25J71R80L541；FCC SH6AN54LQ。模块证书不自动覆盖贴身整机 |
| 固件支持 | EMP客户开发版声称提供标准SDK；商城无EMP后缀，实物是否开放烧录、公开BSP未确认 | 可烧自有固件；参考电路提示修改LFCLK；本次未核获专门NCS3.4.2 BSP |

依据：[HJ手册§2/3/5/6](https://atta.szlcsc.com/upload/public/pdf/source/20261009/5C637C454D23CBC0ADC1CBC2D6490186.pdf)、[Raytac手册p6–9、16、32、34、50](https://atta.szlcsc.com/upload/public/pdf/source/20260417/196CB29ADDF9F5C5D77AA7083ED7C2B7.pdf)。HJ宣传的BLE6.0/6.3文字并不一致；本项目按芯片及实际NCS协议栈支持能力判断，不按商品标题判断。

HJ底部焊盘图的参考间距有0.55mm与0.45mm，焊盘宽约0.35mm。这不是可人工检查的半孔模块；**必须在定板前确认经济型对该LGA封装的接受情况与X-ray费用**。SMT库收录只证明可供选料，不能替代工艺审核。

### 所需接口能否引出

HJ引脚表明确：P1.12=脚17、P1.13=脚14，可保留PDM；SWDIO=脚2、SWDCLK=脚28、nRESET=脚37；P1.14/AIN7=脚32，P1.15=脚31。UART可从适配外设实例的P0/P1/P2引脚选取。Raytac也引出SWD、RESET、P1.12/P1.13及ADC脚；实际布线须按模块引脚表重新映射，不沿用模块焊盘号。

nRF54L15 **PDM20的PSEL应使用P1端口**，不必固定12/13，但不能随便改成P0或P2；ADC只能接AIN功能脚。UARTE实例与端口有对应关系，部分P2功能需要恒定延迟模式，可能增加耗电。QFN48只有P1.00–P1.14，**没有XIAO CSP版的P1.15**。来源：[Nordic引脚规划](https://academy.nordicsemi.com/courses/nrf54l-series-express-course/lessons/lesson-2-power-domains-event-system-and-gpio/topic/gpio-ports-and-pin-planning/)、[端口能力](https://docs.nordicsemi.com/r/bundle/ps_nrf54l15/page/gpio.html-concept_port_capabilities?contentId=SrebAEVg_zxDh~GtLn5_jg)。

### QFN48方案

若HJ最终不能开放烧录或不能满足生产/射频要求，则把 **NRF54L15-QFAA-R＋Nordic QFN48参考设计**列为主后备，而非改用来源不明模块。当前裸片国内自营和SMT均缺货，尚不具备立即下单条件。参考电路还需32MHz晶振、可选32k晶振、4.7µH电感、去耦与0201射频匹配料、天线，**这些配套型号的国内库存未逐一闭合，故不是已完成的备用生产BOM**。

面积只能作本体比较：HJ约20.25mm²、QFN本体36mm²、Raytac本体130.15mm²；这是尺寸乘积，不能当整板节省面积。比Raytac理论上可缩小主控区域，但相对HJ裸片反而更大；真实射频布局面积**未确认**。Nordic参考中有0201料，而经济型最小0402，不能直接承诺经济型复刻参考布局。改料、层叠、地与天线都会改变射频。对硬件新手的v1，模块路径更值得先解决。来源：[参考布局页](https://www.nordicsemi.com/Products/nRF54L15/Reference-layout?lang=zh)、[QFN48参考布局0.8](https://nsscprodmedia.blob.core.windows.net/prod/software-and-other-downloads/reference-layouts/nrf54l15/nrf54l15-qfaa-reference-layout-0_8.zip)、[官方参考电路](https://docs.nordicsemi.com/r/bundle/ps_nrf54l15/page/chapters/ref_circuitry.html-concept_refcircuit_config_1)。

## 4. 麦克风与声孔

Seeed 官方公开的[XIAO Sense V1.2原理图](https://files.seeedstudio.com/wiki/XIAO_nRF54L15/res/202004618_XIAO_nRF54L15_Sense_V1.2_SCH_260605.pdf.pdf)确认麦克风U8为 **MSM261DGT006**，P1.12送时钟、P1.13收数据、L/R接地；3.3V经TPS22916CNYFPR供电，P0.01控制开关。**DGT006确切完整参数及国内同款可供库存未确认。** 国内精确搜索被商城替换成DGT003，页面明确提示型号不同，不能据此声称搜到了原件。

推荐替代 DGT003 的原厂手册给出：1.6–3.6V；标准PDM时钟1.1–4.0MHz，1.8V/2.4MHz条件下典型670µA；低功耗150–900kHz，在768kHz条件下典型290µA；时钟≤50kHz时典型睡眠1µA。顶进音，4×2×1.1mm，64dB SNR。上电最长20ms。**固件当前允许1–3.5MHz，换件后最低值需收紧到1.1MHz，并检查驱动实际选出的时钟。** 16kHz采样率不等于16kHz PDM时钟；后者是高速比特流时钟。[原厂手册](https://atta.szlcsc.com/upload/public/pdf/source/20250417/FFCE6EA3A1F270F7DA3F96571552317A.pdf)。

顶进音可直接对上壳声孔，利用软胶圈形成短而密封的声道，防尘网固定在壳上，胶和泡棉不要堵孔；不能把电池盖在麦克风声孔上。不要气枪吹孔、吸孔或让清洗液进入。具体外壳孔径、防尘网声阻和腔体尺寸未确认，要用组装样机录音比较。

若后续选底进音麦克风，PCB需按**该型号厂家推荐焊盘**开声孔，孔下无电池遮挡；密封圈连接PCB声孔与外壳孔，声孔不涂胶、不塞孔。没有最终型号前不编一个通用孔径。[Infineon封装说明](https://community.infineon.com/t5/Knowledge-Base-Articles/XENSIV-MEMS-microphones-package-FAQs/ta-p/852515)与[装配清洗说明](https://community.infineon.com/t5/MEMS-microphones/PCB-cleaning-with-IFX-MEMS-microphones/td-p/1230297)支持这种处理。

## 5. 充电、电池和插座

### 充电比较

| 项目 | BQ25185 | MCP73831 | TPOWER TP4057 |
|---|---|---|---|
| 40–60mA精度 | ≥40mA有±10%规格；还需计电阻公差 | 规格表有100mA与15mA测试点；40–60mA独立保证未确认 | 规格表有500mA点；40–60mA精度未确认 |
| 50mA名义设定 | 6kΩ；I=300/R（A、Ω） | 20kΩ | 需按该厂家型号复核，不按同名其他厂料推算 |
| 无USB电池耗电 | 典型4µA、最大5µA | 典型0.15–0.25µA、最大2µA，视输入状态 | 睡眠BAT漏电典型3µA、最大5µA |
| 边充边录 | 独立SYS，支持power path | 无独立SYS，需要额外电源分流 | 无独立SYS，需要额外电源分流 |
| 本次取舍 | 首选，功能完整 | 仅充电时停机或愿意增加分流电路才考虑 | 低价但两片省不了多少，低电流保证不足 |

来源：[TI](https://www.ti.com/lit/ds/symlink/bq25185.pdf)、[Microchip](https://www.microchip.com/content/dam/mchp/documents/APID/ProductDocuments/DataSheets/MCP73831-Family-Data-Sheet-DS20001984H.pdf)、[TPOWER](https://datasheet.lcsc.com/datasheet/pdf/093610ce9300ede501b5eb2790fadaea.pdf?productCode=C440390)。TP4057文档电气表测试条件写12V却与绝对最大7V等条款不一致，不能照12V使用。

Power path就是把“给系统供电”和“给电池充电”分开管理；若简单充电器BAT脚并着5–10mA负载，会影响约5mA级终止判断。Microchip有[AN1149分流方案](https://www.microchip.com/en-us/application-notes/an1149)，但多出MOSFET等器件。BQ25185的USB供电SYS可到4.5V，**后面仍需稳压，绝不能直连3.6V上限的主控**。必须配好4.2V电池档、输入限流、ISET、TS温度检测等引脚，不能仅接电阻就视作完成充电设计。

### 电池实际规格示例

以下仅用原厂公开规格核定设计边界，不是采购渠道推荐。三位尺寸代号不保证容量、保护板尺寸或认证一致。

| 规格文件 | 容量 | 标准/最大充电电流 | 文件中的带保护外廓 | 证书状态 |
|---|---:|---:|---|---|
| [LP302020](https://www.fpbattery.com/wp-content/uploads/2023/10/302020-3.7V-100mAh-Specification.pdf) | 100mAh | 20/50mA | 22×20×3mm | 列认证类别；具体UN38.3报告与CCC证书未确认 |
| [另一302020](https://www.fpbattery.com/wp-content/uploads/2024/05/fpbattery-302020-3.7V-90mAh-Lithium-Polymer-Battery-Specification.pdf) | 90mAh | 18/45mA | 22×20×3mm | 未确认 |
| [LP402020](https://www.fpbattery.com/wp-content/uploads/2023/10/402020-3.7V-120mAh-Specification.pdf) | 120mAh | 24/60mA | 22×20×4mm | 未确认 |
| [502020](https://www.fpbattery.com/wp-content/uploads/2024/05/fpbattery-502020-3.7V-150mAh-Lithium-Polymer-Battery-Specification.pdf) | 150mAh | 30/75mA | 22×20×5mm | 未确认 |

这些例子有保护参数，但不能据此认定用户最终买到的同代号电池也有保护。还要索取带线/插头总装图、厚度公差、充放电条件和对应型号的UN38.3测试摘要/CCC证书。保护板不代替充电器或温度监测；两线插座本身不提供NTC温度信号。若保留两线电池，应安排贴近电池的温度检测并验证热耦合，**其具体器件和机械方案未定**。

推荐先按**40mA（7.5kΩ、1%）**评估小电池，最大电流估计40×1.10/0.99≈44.4mA，可覆盖上述最大45mA的示例；最终仍按选中电池手册。设50mA时，上偏差可能超过最大50mA电池的限值。示例规格0.5C是最大而非标准充电速率，不能把“目标0.5C”当所有电池的默认安全额定值。

### 插座

MX1.25只说明一类1.25mm间距结构，不等于JST GH。所选讯普图纸标额定**1A**，高3.6±0.1mm；[JST GH官方系列](https://www.jst-mfg.com/product/index.php?lang=2&series=105)也为1.25mm、1A（AWG26条件）但锁扣、壳体与端子结构不同，不能互插承诺。不要混用GH壳与MX座。必须指定成套线端型号，PCB丝印写清正负，先用万用表量电池插头极性再接；市场线色或同外观插头不保证极性统一。

## 6. 供电、开关与电量采样

建议电源链：`USB 5V → BQ25185 IN；保护电池 → BAT；SYS → TPS62840 → 1.8V主控和麦克风`。拨动开关接EN控制端，不走主电流。整个链路还需去耦、充电配置和保护件。

| 稳压 | 静态/关机电流（典型） | 5–10mA系统负载的效率判断 | 取舍 |
|---|---|---|---|
| TPS62840 | 60nA / 25nA | 1.8V输出典型曲线在毫安段约85–93%；各VIN×5/10mA精确值未确认，非保证值 | 比LDO多电感、较贵，但持续录音更值得省转换损耗 |
| TPS7A02 1.8V | 25nA / 3nA | 理想近似η=Vout/Vin；输入3.0/3.6/4.2V时60%/50%/42.9%，5与10mA近似一样 | 更简单，适合先跑通但不利续航 |
| 3.3V LDO | 同系列需选3.3V型号 | 3.6/4.2V输入约91.7%/78.6%；3.0V不能稳到3.3V | 不能据此保证用完电池容量 |

来源：[TPS62840](https://www.ti.com/lit/ds/symlink/tps62840.pdf)、[TPS7A02](https://www.ti.com/lit/ds/symlink/tps7a02.pdf)。Buck也不能在输入3.0V时稳出3.3V，100%占空模式不是升压；因此1.8V更容易覆盖电池范围。TPS62840输入上限6.5V，可承受BQ的4.5V SYS。自动省电模式下才适合低耗应用；不要误配强制PWM（其空载电流可能达mA级）。电感采用TI推荐2.2µH，**不是把nRF内部DC/DC的4.7µH搬给外部Buck**。

1.8V兼容DGT003和nRF54L15，但调试器、UART电平必须匹配。nRF内部还有DC/DC，外部改1.8V不等于整机电池电流按电压同比例下降。必须测电池端平均功率、BLE峰值供电和录音底噪。

**主电源开关建议用Buck自身EN，独立TPS22916仅作比较项。** TPS22916系列典型关断漏电约10nA，但上述BGA货号的小间距和经济型兼容未闭合，也不是原XIAO的CN型号。不要为照搬板子额外引入BGA。关机仍有充电芯片、保护板、分压、ESD与接口回灌电流，25nA不是整机关机电流。

原XIAO的采样：两个100kΩ、10nF、负载开关；**P1.14/AIN7是采样，P1.15才是使能**，本地`firmware/recorder/app.overlay`也确认ADC7。1.8V系统中4.2V/2=2.1V，不能原样使用。建议把比例改到至少约1/3，并保留高侧隔离防关机回灌；具体阻值、开关和采样稳定时间在原理图阶段验证。常接高阻分压虽然能省开关，但会持续漏电，且关机时可能通过ADC保护结构反向供电，因此**本次不推荐直接删隔离**。若用1MΩ+499kΩ，纯分压在4.2V约2.8µA，这是计算示例而非已选定BOM；ADC源阻抗/建立时间仍要核算。

**续航敏感项：** 容量选择、1.8/3.3V、Buck/LDO、LFCLK、LED占空比、麦克风模式与BLE连接参数都受未实测5–10mA影响。以电池端电流计，90/100/120/150mAh对应理想9–18/10–20/12–24/15–30小时，实际还会受容量衰减、保护截止和转换损失影响。全天目标必须先定义为16h还是24h，再测真实平均电流。

## 7. USB-C 与保护

采用非沉板TYPE-C-31-M-12作为现货候选。它有通孔固定脚，板上高度约3.26mm，图纸含腿总高4.70mm不能直接当作板上高度，也不能忽略底部固定脚与电池间的绝缘/间隙。经济型能否按纯SMT回流完成该固定脚焊接、是否产生插件费，**需最终工艺审核**；若要求额外人工插件，可另换全贴片固定脚座，不能靠沉板绕开。

只充电时CC1、CC2各自一颗**5.1kΩ**到GND，不能两根CC短接共用一颗；无需PD协议去请求更高电压，D+/D−/SBU不用就不接内部电路。设置输入电流上限符合默认USB供电能力，不能见Type-C座便默认可取3A。[Renesas USB-C说明](https://www.renesas.com/en/support/engineer-school/usb-power-delivery-03)。

建议VBUS入口放合适TVS和输入电容，CC各加ESD器件；壳体就近接地并给短回流路径。不接内部电路的数据脚无需为了凑齐USB数据保护阵列而加器件。**PESD5V0S1UL只列为CC候选；不能未经校核就推荐它作为VBUS最终保护。** 该件Vrwm为5V、较大浪涌下钳位可达20V，需对比USB实际电压容差及BQ输入绝对最大值；VBUS应选工作电压覆盖合法输入、钳位低于下游允许值的型号，本次未完成具体料号确认。ESD是静电保护，不等于浪涌/过压全保护，也不代表通过了整机测试。[Nexperia规格](https://assets.nexperia.com/documents/data-sheet/PESD5V0S1UL.pdf)。

## 8. 烧录、日志与固件移植

**CMSIS-DAP＋pyOCD可以作为技术路线，不必默认买J-Link。** pyOCD的[nRF54L源码](https://raw.githubusercontent.com/pyocd/pyOCD/main/pyocd/target/family/target_nRF54L.py)实现了CTRL-AP ERASEALL，auto_unlock可擦除普通APPROTECT并恢复访问；这会清空固件和数据，不等于能绕过ERASEPROTECT。本次未连接硬件，也未验证当前本机pyOCD版本，不能写成已经烧录/recover成功。

| 探针 | 电平/作用 | 价格证据与结论 |
|---|---|---|
| Raspberry Pi Debug Probe SC0889 | CMSIS-DAP SWD＋UART，固定3.3V IO | 官方发布价US$12，国内现价未确认；**不适合直接接1.8V板**。[官方公告](https://www.raspberrypi.com/news/raspberry-pi-debug-probe-a-plug-and-play-debug-kit-for-12/)、[说明](https://www.raspberrypi.com/documentation/microcontrollers/debug-probe.html) |
| NXP MCU-Link Base / MCU-LINK | CMSIS-DAP，VTref跟随1.2–5V | 更适合1.8V候选；国内现价未确认。本次未实测其与本项目pyOCD组合。[NXP产品页](https://www.nxp.com/products/processors-and-microcontrollers/arm-microcontrollers/i-mx-rt-crossover-mcus/mcu-link-debug-probe%3AMCU-LINK)、[UM11931](https://www.nxp.com/docs/en/user-manual/UM11931.pdf) |
| J-Link | 作为兼容性排障备选 | 具体版本电平/授权/价格未确认；不列必购物料 |

建议测试焊盘6点：GND、VTref、SWDIO、SWCLK、RESET、UART_TX；需双向命令加UART_RX第7点。VTref是电平参考，不是把探针电源强灌进板。小批量用有定位的弹簧针夹具，PCB下方有支撑，避免压到麦克风、模块或电池；焊盘尺寸/间距随所选针头定，不报未经夹具验证的最小面积。Tag-Connect可省常驻插座，但应连定位孔一起用[官方封装](https://www.tag-connect.com/technical)。

Zephyr已有[XIAO板定义](https://docs.zephyrproject.org/latest/boards/seeed/xiao_nrf54l15/doc/index.html)，建议从**项目NCS v3.4.2实际所带版本**复制为自有板名，而不是修改SDK文件。板级定义就是告诉系统时钟、电源和各引脚如何连接。要改：

- PDM/UART/LED/ADC pinctrl和`chosen`节点；移除IMU及XIAO调试MCU依赖。
- 麦克风供电、延时、L/R边沿、PDM最低时钟改为DGT003允许范围。
- ADC比例及采样时间、分压开关；注意QFN没有P1.15。
- LFCLK究竟用晶振还是内部RC，精度/校准设置要与实物匹配。
- 芯片内部DC/DC是否有电感、外部Buck设置；不能把两种DC/DC混淆。
- board.yml、Kconfig、DTS及runner目标；pyOCD目标仍需确认`nrf54l`。

工程估计：板级初稿半天到1天，首板验证录音/BLE/电流另1–3天；硬件问题不包含在这段时间里。无需重写Opus或BLE协议，但不能因编译成功就认为移植完成。

## 9. 国内工艺、厚度与预算

### 工艺约束

[嘉立创2026-11-04工艺收费页](https://www.jlc.com/portal/server_guide_26.html)与[SMT能力](https://www.jlc-smt.com/vtechnology)：经济型板厚0.8/1.0/1.2/1.6mm，**不含0.6mm**；最小板10×10mm，无需另加工艺边和Mark，单面；最小0402、IC引脚间距0.4mm、BGA球间距0.5mm；不支持沉板、夹板结构。标准型最小70×70mm，小板需要拼板/工艺边。25×23mm本身不触发经济型最小尺寸问题，但不代表所有候选封装都符合。

PCB四层常规能力与SMT是两套限制：[PCB工艺页](https://www.jlc.com/portal/vtechnology.html)列1oz多层线宽线距最小0.09/0.09mm、多层孔/焊盘极限0.15/0.25mm；建议设计孔≥0.2mm、焊盘比孔大≥0.15mm。v1优先更宽松规则，不为小几毫米强行走极限。四层0.6mm裸板能否下单不等于经济型能贴；0.8/1.0mm均可列候选。

[国内教程](https://www.jlc.com/portal/server_guide_51477.html)及官网说明PCB通常5片起、SMT2片起，因此预算按买5片裸板、只贴2片。没有实际Gerber/BOM前无法确认精确下单配置、贴片损耗与交期。

### 厚度叠层

先给**不把连接器和电池错开**的保守计算。壳厚、泡棉、余量是设计输入，不冒充元件规格。真实元件高度见BOM图纸；最大电池厚度公差未取得。

| 自上而下 | 302020 | 402020 | 性质 |
|---|---:|---:|---|
| 上壳壁 | 0.8mm | 0.8mm | 设计假设，打印材料待定 |
| 器件/壳间隙 | 0.3 | 0.3 | 设计假设，不压元件 |
| 最高元件包络 | 3.7 | 3.7 | MX插座3.6+0.1mm；还需校核插头出线 |
| PCB | 0.8 | 0.8 | 经济型下限，未加板厚公差 |
| 绝缘/缓冲层 | 0.5 | 0.5 | 设计假设，需避开USB固定脚，不能直接压电芯 |
| 电池 | 3.0 | 4.0 | 公开规格示例，非最终成品最大值 |
| 下壳壁 | 0.8 | 0.8 | 设计假设 |
| **合计** | **9.9mm** | **10.9mm** | **计算值，不含全部制造公差/鼓胀** |

模块本体不是这一叠层的最高物。若把USB和电池座放在电池侧边、避免上下重叠，电池区域上方可按约1.5mm器件包络试排，计算变成7.7/8.7mm；这只是布局目标，未证明所有电源件、公差、声道都能满足。需要更大PCB边带、插头插拔空间和22×20mm电池腔，因此建议先约30×25mm试排再缩，不能保证25×23mm整机。

天线放板角且位于电池外侧，所有层遵守厂家禁布，远离USB壳、紧固件、挂绳金属及人体贴靠侧。电池铝塑膜与人体会改变天线阻抗、吸收射频；模块手册没有量化本整机衰减，不能承诺“留几毫米即可”。用最终电池和外壳对比裸板/装壳RSSI、断连和录音丢包。螺丝/卡扣锁外壳、定位柱托PCB，装配力不能靠挤压模块和电池承担。20g目标尚无完整材料/电池/外壳质量表，**未确认**。

### 国内收费与2片预算

[国内官方收费图](https://pbt-pub.jlc.com/pbt/cms/586fcc60-31ea-4833-923b-68f3050267f7-file)明确为估价参考，以系统为准：经济型工程费¥50/单、钢网免费、¥0.01/焊点、扩展料¥20/种；标准型单面工程费¥150、钢网¥50、上料¥8/种。两者扩展/上料有¥800封顶。插件工程¥20、初阶¥0.1/点，X-ray初阶¥10/个；是否对本方案触发及具体计数未确认。**不是海外JLCPCB美元收费。**

| 分项 | 两片预算 | 依据/是否实价 |
|---|---:|---|
| 模块、麦克风、充电、Buck、拨动 | ¥138.272 | (30.45+5.47+9.48+23.42+0.316)×2，国内SMT页面单价；不含损耗 |
| 电感、插座、USB、CC保护 | 约¥6 | 用商城阶梯价算的装板用量近似；SMT两片采购价未确认，不按此承诺 |
| 电阻电容、LED、温度/采样/保护配套 | ¥10–25 | 工程预留，BOM未闭合 |
| 最小包装、损耗备料 | ¥10–30 | 工程预留；昂贵模块若需多备一颗需另加约¥30.45 |
| 5片四层0.8mm裸板 | ¥20–80 | 预算占位，**未获正式报价**，无默认免费券 |
| 经济型工程费＋钢网 | ¥50＋0 | 官方规则 |
| 贴装焊点 | ¥3–5 | 假设每板150–250点×2×¥0.01；未统计真实焊盘 |
| 换料费 | ¥180–240 | 假设9–12种收费扩展料；ADC/温度/TVS可能增加料种 |
| X-ray/插件/运费 | ¥20–60 | 预算预留，不是实际报价 |
| **合计** | **约¥437–634，建议留¥450–650** | 条件预算，不含电池、外壳、夹具、探针；工艺转标准型需重算 |

由于换料费按种类收，不按两片元件用量收，低价芯片省一两元常被换料费淹没。标准型也不一定总比经济型贵：单面固定费200加8×料种，与经济型50加20×扩展种需按真实BOM比较；但标准型拼板与钢网又占空间/费用，不能只比较工程费。

免费券规则见[国内领券页](https://www.jlc.com/coupons)：通用券与EDA专用券不同，是否满足上月实付、已发货等条件影响领取；券有期限与工艺范围。**本次没有验证账号当前拥有的可用通用券，也没有领取/使用优惠**，KiCad项目预算不按0元裸板处理。

### KiCad交付

下单需Gerber（四层铜、阻焊、丝印、板框）与Excellon钻孔ZIP；BOM含位号、数量、封装、完整型号、立创编号；坐标文件含位号、X/Y、旋转、面。原理图自定义LCSC字段，BOM剔除DNP；坐标用mm、与钻孔原点统一，单面front。KiCad菜单从Fabrication Outputs导出Gerber/Drill/Component Placement；CLI例如`kicad-cli pcb export pos --format csv --units mm --side front --use-drill-file-origin --exclude-dnp`，再按国内模板调整表头，不能假设海外插件模板直接兼容。最终上传预览逐颗核对1脚方向、极性、USB与麦克风。来源：[KiCad 9 PCB手册](https://docs.kicad.org/9.0/en/pcbnew/pcbnew.html)、[CLI](https://docs.kicad.org/9.0/en/cli/cli.html)、[国内SMT入口](https://www.jlc-smt.com/order)。

## 10. 开源参考：能借什么，不能照抄什么

**Omi consumer硬件**：[官方入口](https://docs.omi.me/doc/hardware/consumer)、[电子设计](https://docs.omi.me/doc/hardware/consumer/electronics)、[装配](https://docs.omi.me/doc/hardware/consumer/assembly)、[主板原理图](https://raw.githubusercontent.com/BasedHardware/omi/main/omi/hardware/consumer/electrical/mainboard/schematic.pdf)、[KiCad 9仓库说明](https://github.com/BasedHardware/omi/blob/main/omi/hardware/consumer/README.md)。

- 用BQ25101，原理图设135mA；不是BQ25185，不能搬给小电池。Buck是TPS628438。
- 2×T5838顶进音；装配列5.8×4.0×0.1mm防尘网。可借鉴独立声道、防尘网，不能当底进音PCB声孔例子。
- PCB靠框架定位，电池单独固定、硅胶垫、螺丝闭壳，适合借鉴承力方式。
- 保留SWD、RESET、UART和电源调试信号；本项目可缩为焊盘。
- RF经弹簧触点及复杂射频网络，电池到辐射体的具体净空本次未确认。21×21×0.6mm主板用盲埋孔、0201、WLCSP、nRF5340+nRF7002，不能当嘉立创经济型制造尺寸依据。

**ZSWatch**的[快速指南](https://zswatch.dev/docs/getting-started/quickstart?hardware=zswatch)用Dock提供1.8V VTref，复用USB-C脚做SWD/UART；[作者硬件讲稿](https://archive.fosdem.org/2025/events/attachments/fosdem-2025-5067-zswatch-the-open-source-smartwatch/slides/237834/FOSDEM_20_IMtfTmF.pdf)展示nRF53模块、nPM1300及麦克风。可借鉴目标电平与独立调试板，不照搬USB调试复用；它不是已核读全部充电/声孔尺寸的替代原理图。

**OpenEarable 2**：[官方库](https://github.com/OpenEarable/open-earable-2)、[结构版本说明](https://blog.openearable.com/announcements/hardware/2025/11/20/openearable-hardware-201-released.html)。项目持续迭代，结构更新指出只改外壳也改善无线；支持本项目必须装壳测BLE的判断。本次未逐页审其硬件原理图，充电料号、声孔尺寸、天线具体净空不作推断。

## 11. 风险、未确认与真正需要决定的事

### 进入画板前的阻断项

1. **HJ具体C编号配置**：能否擦写自有NCS固件、32k晶振是否装、天线匹配/净空、LGA45经济型可贴性。没有这些，不能冻结主控封装。应由使用者授权后询问厂家/工厂；本次未替用户发消息。
2. **实测电池端功耗**：连续录音、Opus、真实手机BLE连接一起测，关闭长期调试日志，记录平均及峰值。用它定容量与全天定义。
3. **最终电池**：容量、成品最大尺寸、保护/极性、最大充电电流及证书、温度检测方式。20×20mm底面假设目前未获验证。
4. **布局与完整BOM**：连接器固定脚工艺、ADC隔离、温度网络、VBUS TVS、被动料未闭合；预算与厚度尚不是生产承诺。

其他未确认项：模块佩戴整机射频效果、实际探针/pyOCD recover、各工作电压下音质和电流、整机20g、账号优惠券、最终生产损耗/交期。库存可变，下单前需复核；本次结论不能锁货。

### 使用者决策

| 决定 | 推荐 | 推荐在什么情况下是错的 |
|---|---|---|
| 主控方向 | 先确认HJ配置，继续模块路线；暂不转裸片 | 模块不能开放烧录、天线效果不够或经济型不可贴；届时评估QFN＋标准型，当前裸片缺货也要解决 |
| 厚度与长宽 | 先允许约30×25mm试排，并把高连接器与电池错开；不要先锁25×23×<10 | 长宽是绝对硬约束；则可能必须换更低插座/取消插座或接受更厚，这属于产品取舍 |
| 电池与续航 | 先测功耗，再选容量，充电初步按40mA评估 | 实测需要更大电池才能全天，或最终电池允许条件不同；不能为薄强行保留302020 |
| 供电与调试 | 1.8V Buck，使用VTref跟随探针 | 已有工具只能3.3V且不愿更换，或实测Buck噪声/功耗收益不成立；需重新比较3.3V方案而不是硬接电平 |
| 开关和麦克风 | 拨动控制Buck EN；采用有现货的DGT003 | 必须独立切断全部系统支路、或DGT003音质测试不合格；需再选隔离/麦克风，不是直接照搬XIAO |
| 预算 | 两片PCBA先留¥450–650，模块审核后再做正式报价 | 预算上限明显更低；可减少扩展料种或改工艺比较，不能通过省充电验证和保护来压价 |

**下一步顺序：先测真实功耗和确认模块/电池，再定原理图与结构。** 这份调研已给出有库存的候选和主要冲突，但没有把未知项包装成可直接下单的完整设计。
