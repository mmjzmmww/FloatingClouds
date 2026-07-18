#!/system/bin/sh
F=/data/data/com.tencent.mm/shared_prefs/mask_wechat_options.xml
sed -i 's/&quot;hideMainConvList&quot;:true/\&quot;hideMainConvList&quot;:false/' "$F"
echo "patched hideMainConvList -> $(grep -o 'hideMainConvList[^,}]*' "$F")"
