
			var qihao = '31410000';
			var countNum = 0;
			var firstNum = 0;
			var msgconf = {
				isAutoBottom: true,
				newMsgCount: 0,
				nickName: '大家好',
				uid: 'local-demo',
				flag: 0,
				isVip: false,
				currentTop: 0
			};
			let m_drawTime = 0;
			var m_drawTimeBase = 0;    // 上次从服务器获取倒计时的本地时间戳
			var m_drawTimeServer = 0;  // 服务器返回的原始剩余秒数
			var isLoad = false;
			var recordList = [];
			var gameType = '17';
			var gameWf = '';
			var qiuIndex = 8;
			var kuaijie = false;
            var ws;
        
            var layer = layui.layer, form = layui.form, table = layui.table;
              
            	form.on('submit(formDemo)', function(data){
            	    var data = data.field,
            	        numList = $('.kuaikey .numberStyle'),
            	        numArr = [];
        			for (var i = 0; i < 5; i++) {
        				numList.eq(i).text(data['num'+i]);
        				numArr.push(data['num'+i]);
        			}
        			$.ajax({
        				type: "POST",
        				url: "/chat/index/setnum",
        				dataType: "json",
        				data: {
        					"str": numArr.join(',')
        				},
        				success: function(data) {
        				    layer.closeAll();
        				}
        			});
                    return false;
                });
			$(document).ready(function() {
				var txtmsg = $("#txtMsg");
				var msgcontent = $(".msgcontent");
				var autoMsgSize = function() {
					if (!$(".divBottom").hasClass("msgdown")) {
						$(".divBottom").removeClass("msgup");
						$(".divBottom").addClass("msgdown");
					}
					txtmsg.css({
						"top": $(window).scrollTop(),
						"visibility": "visible"
					});
					msgcontent.css({
						"top": $(window).scrollTop(),
						"height": document.documentElement.clientHeight - $(".divBottom").height() - $(
							window).scrollTop()
					});
					$(".aotuheight").css("height", document.documentElement.clientHeight + 0);
					$(".divBottom").css("top", $(window).scrollTop() + msgcontent.height()); //
				};
				window.autoMsgSize = autoMsgSize;
				function scrollBtm() {
					msgcontent.scrollTop(msgcontent[0].scrollHeight - msgcontent.height() - 1);
					$(".divBottom .btndown:visible").hide();
					msgconf.newMsgCount = 0;
				}
				$(".divBottom .btndown").on("click", function() {
					msgconf.isAutoBottom = true;
					scrollBtm();
				});
				var lastSTop = msgcontent.scrollTop();
				var newMsgB = $(".divBottom .btndown b");
				var checkAutoScroll = function() {
					var sTop = msgcontent.scrollTop();
					if (lastSTop != sTop) {
						if (sTop < lastSTop) {
							msgconf.isAutoBottom = false;
						}
						lastSTop = sTop;
						return;
					}
					if (msgconf.isAutoBottom == false && Math.abs(msgcontent[0].scrollHeight - msgcontent.height() -
							sTop) < 50) {
						msgconf.isAutoBottom = true;
					}
					var newMsgTxt = msgconf.newMsgCount + "条新消息";
					if (msgconf.isAutoBottom) {
					    scrollBtm();
					} else {
						$(".divBottom .btndown:hidden").show();
					}
					if (newMsgB.text() != newMsgTxt) {
						newMsgB.text(newMsgTxt);
					}
				};
				setInterval(checkAutoScroll, 500);
				setInterval(updateTime, 1000);
				$(window).resize(autoMsgSize);
				//提交消息
				var submitMsg = function(qiu_index = 0,cmd_text = "") {
					if (cmd_text.length > 0) {
						var sendContent = cmd_text
					} else {
						var sendContent = $.trim(txtmsg.val());
					}
					if (/\S/.test(sendContent)) {
						var playNoticeCmd = $.trim(sendContent);
						window._pendingPlayNotice = /^(?:第\s*\d+\s*球\s*)?(?:玩法|说明)$/.test(playNoticeCmd);
						(function() {
							if (!kuaijie) {
						        sendContent = getQiu(qiu_index) + sendContent;
							} else {
            					if (sendContent == '历史') {
            						// 历史命令加上球号前缀，如"第7球/历史"
            						sendContent = getQiu() + sendContent;
            					} else if (sendContent != '取消' && sendContent != '玩法' && sendContent != '流水' && sendContent != '查' && sendContent.indexOf("上") != -1 && sendContent.indexOf("下") != -1) {
            						sendContent = getQiu() + sendContent;
            					}
							}
							kuaijie = false;
							// 发送前检查WebSocket连接状态
							if (!ws || ws.readyState !== WebSocket.OPEN) {
								layer.msg('网络连接中断，请稍后重试', {icon: 2, time: 2000});
								return;
							}
							var div = "<div class='msglayer msg0 msgright' ids='0'><div class='fpop'><div class='headimg'><img src='"+'/hby019-demo/source/static/reference-player/avatars/u__chat_img13.jpg'+"' /></div><h5 class='uname'>"+(msgconf.nickName || "自己")+"</h5><div class='msg-loader'><div class='throbber-loader'>Loading…</div></div><pre class='msgpre'>"+sendContent+"</pre></div></div>";
							msgcontent.append(div);
							var text = {
								"dluser": msgconf.nickName,
								"wxid": 'wxid_local_demo',
								"cmd": sendContent,
								'qh': qihao,
								'je': $('.je').val(),
								'qiuIndex': qiu_index > 0 ? qiu_index : qiuIndex
							};
                            ws.send(JSON.stringify(text));
                            // 5秒超时：如果进度条还在就自动清除，提示发送超时
                            setTimeout(function() {
                                if ($(".throbber-loader").length > 0) {
                                    $(".throbber-loader").remove();
                                    layer.msg('发送超时，请重试', {icon: 2, time: 2000});
                                }
                            }, 5000);
					        scrollBtm();
						})();
					}
					txtmsg.val("");
					// 同步清空 Alpine 的 content 变量，避免下次输入时残留上次内容
					var alpineEl = document.querySelector('.divBottom');
					if (alpineEl && alpineEl._x_dataStack) {
						alpineEl._x_dataStack[0].content = '';
					}
					// 重置textarea高度
					txtmsg[0].style.height = '1.4em';
					if (typeof checkTxtResize === 'function') checkTxtResize();
					$('.je').val('')
					$(".grid-container .grid-item").removeClass('selected')
					$(".button-row .btn").removeClass('selected')
				};
				$(".divBottom .send").on("touchstart", function(event) {
					event.stopPropagation();
					submitMsg();
					return false;
				});
				$(".divBottom .send").on("click", function(event) {
					submitMsg();
					return false;
				});
				$(".button-row .btn").on("click", function(event) {
					$(this).toggleClass('selected')
					getXiazhu()
				});
				$(".grid-container .grid-item").on("click", function(event) {
					$(this).toggleClass('selected')
					getXiazhu()
				});
				$(".button-money .btn").on("click", function(event) {
					$('.je').val($(this).attr('m'))
					getXiazhu()
				});
				$(".je").on("keyup", function(event) {
					getXiazhu()
				});
				$(".jian").on("click", function(event) {
					var num = $('.je').val();
					if (!num || num == 0) {
						num = 0;
					}
					if (num > 0) {
						$('.je').val(Number(num) - 1)
						getXiazhu()
					}
				});
				$(".jia").on("click", function(event) {
					var num = $('.je').val();
					if (!num || num == 0) {
						num = 0;
					}
					$('.je').val(Number(num) + 1)
					getXiazhu()
				});
				$(".chouma").on("click", function(event) {
					$('.je').val(0)
					txtmsg.val("");
				});

				function getXiazhu() {
					var big = $('.button-row .btn.selected'),
						small = $('.grid-container .grid-item.selected'),
						je = $('.je').val() ? $('.je').val() : 0,
						str = '';
					for (var i = 0; i < big.length; i++) {
						var t = $(big[i]).attr('t');
						for (var j = 0; j < small.length; j++) {
							var st = $(small[j]).attr('t');
							if (st != '龙' && st != '虎') {
								str += t + $(small[j]).attr('t') + je + ','
							}
						}
					}
					for (var j = 0; j < small.length; j++) {
						var st = $(small[j]).attr('t');
						if (st == '龙' || st == '虎') {
							str += $(small[j]).attr('t') + je + ','
						}
					}
					if (je > 0) {
						txtmsg.val(str.slice(0, -1));
					}
				}
				var isQp = false;
				$(".divBottom .vkb,#txtMsg").on("click", function(event) {
					if ($(".divBottom").hasClass("selekb")) {
						$(".divBottom").removeClass("selekb");
					} else {
						$(".divBottom").addClass("selekb");
					}
					// 切换 Alpine keypad 变量
					var alpineEl = document.querySelector('.divBottom');
					if (alpineEl && alpineEl._x_dataStack) {
						var data = alpineEl._x_dataStack[0];
						data.keypad = !data.keypad;
					}
					// 延迟等 Alpine 渲染完再重算布局
					setTimeout(function() {
						autoMsgSize();
					}, 50);
					return false;
				});
				$(".kuaitou").on("click", function(event) {
					$('.key-box').show();
				});
				$(".cancel").on("click", function(event) {
					$('.key-box').hide();
				});
				$(".ok").on("click", function(event) {
					submitMsg();
					$('.key-box').hide();
				});
				$(".zoushi").on("click", function(event) {
					$('.rule,.orders').hide();
					$('.zoushitu iframe').attr('src', '/hby019-demo/source/chat/index/zoushi.html?' + Math.random());
					$('.zoushitu').toggle();
					if ($('.zoushitu').css('display') == 'none') {
						$('.zoushitu iframe').attr('src', '');
					}
				});
				$(".guize").on("click", function(event) {
					$('.zoushitu,.orders').hide();
					$('.zoushitu iframe').attr('src', '');
					$('.rule').toggle();
				});
				$(".xiadan").on("click", function(event) {
					$('.zoushitu,.rule').hide();
					$('.zoushitu iframe').attr('src', '');
					$('.orders').toggle();
				    getRecord();
				});
				$(".qingping").on("click", function(event) {
					isQp = true;
					$('.msgcontent').empty();
					setTimeout(function() {
						isQp = false;
					}, 2000);
				});
				$('.msgcontent').on('click', '.msgpre button', function(event) {
					var cmd = event.target.getAttribute('data-cmd');
					if (!confirm('是否跟投？')) return;
					if (cmd.indexOf("第") != -1) {
						var qiu_text = cmd.slice(0,3);
						var cmd_text = $.trim(cmd.slice(4));
					} else {
						var cmd_text = $.trim(cmd);
						var qiu_text = "";
					}
					var qiu_index = getQiu2(qiu_text)
					submitMsg(qiu_index, cmd_text)
				});
				// 监听textarea高度变化，自动重算布局，解决输入换行后键盘被遮挡的问题
				var lastTxtHeight = txtmsg.outerHeight();
				function checkTxtResize() {
					var curH = txtmsg.outerHeight();
					if (curH !== lastTxtHeight) {
						lastTxtHeight = curH;
						autoMsgSize();
					}
				}
				// 用MutationObserver监听style变化（Alpine通过style.height调整高度）
				if (window.MutationObserver) {
					var txtObserver = new MutationObserver(function() {
						checkTxtResize();
					});
					txtObserver.observe(txtmsg[0], { attributes: true, attributeFilter: ['style'] });
				}
				// 同时监听input事件，处理直接输入导致的换行
				txtmsg.on("input", function() {
					this.style.height = '1.4em';
					this.style.height = this.scrollHeight + 'px';
					checkTxtResize();
				});

				$(".divBottom .inpvkb li").on("click", function(event) {
					var ele = $(this);
					if (ele.text() == '取消' || ele.text() == '历史' || ele.text() == '玩法' || ele.text() == '流水' || ele.text() == '查' || ele.text() == '上' || ele.text() == '下' || ele.text() == '玩') {
						kuaijie = true;
					}
					if (ele.hasClass("del")) {
						txtmsg.val((txtmsg.val() || "").replace(/[\S\s]$/, ""));
						if (txtmsg.val()=='') {
						    kuaijie = false;
						}
					} else if (ele.hasClass("nline")) {
						if (ele.text().trim() == '玩') {
							txtmsg.val(txtmsg.val() + '玩法');
						} else {
							txtmsg.val(txtmsg.val() + "\r\n");
						}
					} else {
						txtmsg.val(txtmsg.val() + ele.text());
					}
					txtmsg.scrollTop(txtmsg[0].scrollHeight);
					// 手动触发高度调整
					txtmsg[0].style.height = '1.4em';
					txtmsg[0].style.height = txtmsg[0].scrollHeight + 'px';
					checkTxtResize();
				});
				//根据GetMsg结果，创建消息div，其dt为返回的一个item
				var createMsgTag = function(dt, isVip) {
					var div = $("<div class='msglayer msg" + dt.ids + "' ids='" + dt.ids +
						"'><div class='fpop'><div class='headimg' ></div><h5 class='uname'></h5></div></div>"
					);
					div.data("dt", dt);
					div.addClass(dt.rank == 1 ? "msgright" : "msgleft");
					var uname = $(".uname", div);
					var headimg = $(".headimg", div);
					var fpop = $(".fpop", div);
					if (dt.rank == 2) {
						uname.addClass("sys");
					}
					if (dt.wxid == 0 && dt.atUser != 'kai6' && dt.atUser != 'kai3' && dt.atUser != 'stop' && dt
						.atUser != 'last') {
						if (dt.content.indexOf("@") != -1) {
						} else {
							if (dt.content.indexOf("wanfa") == -1) {
								fpop.addClass("corr");
							}
						}
					}
					if (dt.atUser == 'kai6' || dt.atUser == 'kai3' || dt.atUser == 'kai4' || dt.atUser ==
						'kai5' || dt.atUser == 'kai' || dt.atUser == 'kai2' || dt.atUser == 'ck') {
						fpop.addClass("corr2");
						fpop.addClass(dt.atUser);
					}
					if (dt.atUser == "!") {
						fpop.addClass("corrgm");
					}
					if (gameType == '5') {
						uname.addClass("black");
					}
					uname.text(dt.name);
					switch (dt.type) {
						case 1:
							if (dt.content.indexOf("wanfa") != -1) {
								if (window._pendingPlayNotice && typeof showPlayNotice === 'function' && (window._pendingPlayNotice = false, showPlayNotice())) {
									div.hide();
								} else {
									var divImg = $("<pre class='msgimg' style='padding:0;'><img src='" + dt.content +
										"' style='max-width:96%;'></pre>");
									fpop.append(divImg)
								}
							} else {
								var liu = '';
								if (dt.content.indexOf("流水") != -1) {
									if (dt.content.indexOf("盈亏") != -1) {
										fpop.addClass("corr");
									}
									if (msgconf.nickName == dt.wid) {
										liu =
											'<p class="isvip" style="width: 90%;margin: 5px auto;background: #84bb54;color: #fff;text-align:center;line-height: 28px;border-radius: 5px;display:' +
											(isVip == 1 ? 'block' : 'none') + ';">查看详情</p>';
									}
								}
								var gentou = "0";
								var cmd_content = dt.content;
								var copy_button = "<button data-cmd='"+cmd_content+"' style='font-size:12px;width: 50px;height: 25px;border:0px;border-radius: 2px;text-align: center;color: #ffffff;background: #0d8bf2;margin-left: 10px;'>跟 投</button>";
								var pre = $("<pre class='msgpre'" + ((dt.atUser == 'kai3' || dt.atUser == 'kai4' || dt.atUser == 'kai5') ? 'style="width:100%;max-width:100%;box-sizing: border-box;padding-right:6px;line-height: 20px;padding: 6px;margin: 0;box-sizing: border-box;font-variant-numeric: tabular-nums;"' : (dt.atUser == 'kai6' ? ' style="line-height: 18px;width:100%;max-width:100%;padding: 6px;margin: 0;box-sizing: border-box;"' : 'style="font-variant-numeric: tabular-nums;' + (dt.content.indexOf("累计") != -1 ? 'width:100%;' : '') + '"')) +"></pre>");
								if (gentou == 1 && dt.wxid != "0" && dt.rank != 1) {
									if (dt.content.indexOf('上') == -1 && 
										dt.content.indexOf('下') == -1 && 
										dt.content.indexOf('查') == -1 && 
										dt.content.indexOf('玩法') == -1 && 
										dt.content.indexOf('历史') == -1 &&
										dt.content.indexOf('流水') == -1 
									) {
										pre.html(dt.content + copy_button)
									} else {
										pre.text(dt.content)
									}
								} else {
									pre.text(dt.content);
									// 总榜退单通知部分用红色字体显示
									if (dt.atUser == 'kai5' && dt.content.indexOf('退单通知') != -1) {
										var rawHtml = pre.html();
										var parts = rawHtml.split('退单通知');
										if (parts.length > 1) {
											pre.html(parts[0] + '<span style="color:#e53935;font-weight:bold;">退单通知' + parts[1] + '</span>');
										}
									}
									// 封盘后机器人退单通知整条消息用红色显示
									if (dt.atUser == 'tuidan_notice') {
										pre.html('<span style="color:#e53935;font-weight:bold;">' + pre.html() + '</span>');
									}
									// 核对列表底部提示文字用红色显示
									if (dt.atUser == 'kai4') {
										var rawHtml = pre.html();
										var sepIdx = rawHtml.lastIndexOf('-----------');
										if (sepIdx != -1) {
											var sepEnd = sepIdx + '-----------'.length;
											pre.html(rawHtml.substring(0, sepEnd) + '<span style="color:#e53935;">' + rawHtml.substring(sepEnd) + '</span>');
										}
									}
								}
								fpop.append(pre);
								fpop.append(liu);
							}
							break;
						case 2:
							if (dt.content && /\S/.test(dt.content + "")) {
								if (dt.content.indexOf("ck") != -1 && dt.content.indexOf("期数") == -1) {
									var divImg = $(
										"<div class='msgimg' style='width: 100%;max-width: 100%;padding:0 5px;text-align:left;font-size: 16px;font-family: -apple-system,BlinkMacSystemFont,Helvetica Neue,Helvetica,Segoe UI,Arial,Roboto,PingFang SC,miui,Hiragino Sans GB,Microsoft Yahei,sans-serif;font-weight: 500;color:#000;'>" +
										dt.content + "</div>");
								} else {
									var divImg = $("<div class='msgimg' style='padding:0 5px;'>" + dt.content +
										"</div>");
								}
								fpop.append(divImg)
							}
							break;
					}
					if (/\S/.test(dt.headimg || "")) {
						headimg.append('<img src="' + dt.headimg + '" />');
					} else if (dt.rank == 2) {
						headimg.append('<img src="//' + window.location.host + '/hby019-demo/source/static/reference-player/avatars/jqr.png" />');
					}
					return div;
				};

				// 创建居中时间标签（机器人消息专用）
				var createTimeTag = function(dt) {
					if (dt.name == '机器人') {
						var sysTypes = ['kai','kai2','kai3','kai4','kai5','kai6','ck','stop','last'];
						if (sysTypes.indexOf(dt.atUser) !== -1 || (dt.content && dt.content.indexOf('@') !== 0)) {
							var t = dt.time || (new Date().getHours().toString().padStart(2,'0') + ':' + new Date().getMinutes().toString().padStart(2,'0'));
							return $("<div class='msgtime'><b>" + t + "</b></div>");
						}
					}
					return null;
				};
				
                function initWS() {
                    ws = new WebSocket("wss://"+window.location.host+"/ws?uid="+msgconf.uid+"&gt="+gameType);
                    ws.onopen = function() {
                        // 重连成功后，清除所有残留的发送进度条
                        $(".throbber-loader").remove();
                    };
                    ws.onmessage = function (event) {
                        var list = JSON.parse(event.data);
        				var div = createMsgTag(list, list['isVip']);
        				if ($('.msg' + list['ids']).length == 0) {
        					var timeTag = createTimeTag(list);
        					if (timeTag) msgcontent.append(timeTag);
        					msgcontent.append(div);
        				}
					    $(".throbber-loader").remove();
					    scrollBtm();
                    };
                    ws.onerror = function() {
                        // 出错后关闭连接，触发onclose进行重连
                        ws.close();
                    };
                    ws.onclose = function() {
                        // 延迟2秒重连，避免服务器异常时疯狂重连
                        setTimeout(initWS, 2000);
                    };
                }
         
                initWS();
				/** 与底部「第N球」下拉同步：仅第 N 个顶部球为红色，其余为蓝 */
				function syncTopQiuHighlight() {
					var n = parseInt(String(qiuIndex), 10);
					if (isNaN(n) || n < 1) n = 8;
					if (n > 8) n = 8;
					for (var i = 0; i < 8; i++) {
						var $b = $('#qiu' + i);
						if ($b.length) {
							if (i === n - 1) $b.addClass('red');
							else $b.removeClass('red');
						}
					}
					var heads = document.getElementsByClassName('numblueHead');
					for (var j = 0; j < heads.length && j < 8; j++) {
						heads[j].classList.remove('numblueHeadActive', 'ever');
						if (j === n - 1) heads[j].classList.add('numblueHeadActive');
					}
				}
                var _getTimeLoading = false; // 防止getTime重复请求
                function getTime() {
                    if (_getTimeLoading) return; // 上一次请求还没返回，跳过
                    _getTimeLoading = true;
                    var big = $('.item-container'),
						small = $('.bet-button'),
						str = '';
					for (var i = 0; i < big.length; i++) {
						var t = $(big[i]).children('div').children('div').children('div').eq(0).text();
						t = $.trim(t);
							str += t + ','
					}
					for (var i = 0; i < small.length; i++) {
						var t = $(small[i]).children('div').children('span').text();
						t = $.trim(t);
					    str += t + ','
					}
					$.ajax({
						url: "/chat/index/timer",
						type: 'POST',
						data: {str:str},
						success: function(ret) {
						    if (ret.kj.dtOpen > 0) {
							    m_drawTimeServer = ret.kj.dtOpen;  // 记录服务器原始剩余秒数
							    m_drawTimeBase = Date.now();        // 记录获取时的本地时间戳
							    m_drawTime = ret.kj.dtOpen;
							    clearInterval(msgconf.flag);
							    msgconf.flag = null;
						    } else {
						        // 还在开奖中，3秒后再查一次
						        setTimeout(function() { getTime(); }, 3000);
						    }
							var c = ret.kj.Code.split(','),zonghe = 0,v = document.getElementsByClassName('numblueHead').length ? document.getElementsByClassName('numblueHead') : document.getElementsByClassName('blueQiu'),gameJs = '';
							qihao = ret.qh;
							$('.dqhao').text(qihao);
							$('#issue').text(ret.kj.QiHao + '期');
							$('#topissue').text(ret.kj.QiHao);
							for (var i = 0; i < c.length; i++) {
								v[i].innerHTML = c[i].toString().padStart(2, '0');
								$('#qiu'+i).text(c[i]).show();
								zonghe += Number(c[i]);
								var haomahong = (c[i] % 3 == 0 ? 'r75' : (c[i] % 3 == 1 ? 'b75' :
								'g75'));
								var hong = 'ever';
								if (gameJs) {
								    var jsArr = gameJs.split(',');
									if (jsArr.includes(i+1)) {
										hong = 'numblueHeadActive';
									}
								} else {
									hong = 'numblueHeadActive';
								}
								if (!v[i].classList.contains('blueQiu')) v[i].classList.add(hong);
								v[i].classList.remove('blue75', 'green75', 'red75');
								if (ret.kj.type == 75) {
									v[i].classList.add(haomahong);
								}
								v[i].style.lineHeight = '25px';
								$('.blueQiu').css('lineHeight','25px');
							}
							syncTopQiuHighlight();
							if (ret.kj.type == 75) {
								$('.content .zonghe').text(zonghe);
								var longhu = (c[0] > c[4] ? '龙' : '虎');
								$('.content .longhu').text(longhu);
								$('.content .jiahao,.content .zonghe,.content .longhu').parent().show();
								$('.content .jiahao,.content .zonghe,.content .longhu').css('lineHeight',
									'20px');
							} else {
								$('.content .jiahao,.content .zonghe,.content .longhu').parent().hide();
							}
							$('.kjs').empty();
							var kjsHtml = '<span class="kjs-qihao">' + ret.kj.qs + '期</span>';
							for (var i = 0; i < 5; i++) {
								kjsHtml += '<span class="kjs-ball' + (i === 4 ? ' kjs-ball-red' : '') + '">' + c[i] + '</span>';
							}
							kjsHtml += '<span class="kjs-result">';
							kjsHtml += '<span class="' + (ret.kj.da == '大' ? 'color-red' : '') + '">' + ret.kj.da + '</span>';
							kjsHtml += '<span class="' + (ret.kj.dan == '双' ? 'color-red' : '') + '">' + ret.kj.dan + '</span>';
							kjsHtml += '<span class="' + (ret.kj.lh == '龙' ? 'color-red' : '') + '">' + ret.kj.lh + '</span>';
							kjsHtml += '</span>';
							$('.kjs').append(kjsHtml);
						},
						complete: function() {
						    _getTimeLoading = false; // 无论成功失败都解锁
						}
					})
                }
                
                function getMsgRecord() {
					$.ajax({
						url: "/chat/index/msgrecord",
						type: 'POST',
						data: {count:countNum},
						success: function(ret) {
						    var list = ret.Message;
							for (var k in list) {
								dt = list[k];
								var div = createMsgTag(dt, ret.isVip);
								if ($('.msg' + dt.ids).length == 0) {
								    var timeTag = createTimeTag(dt);
								    if (timeTag) msgcontent.append(timeTag);
								    msgcontent.append(div);
								}
							}
				            autoMsgSize();
				            autoMsgNewFunc();
						}
					})
                }
                
                getTime();
                getMsgRecord();
        
                var _autoMsgTimer = null; // 消息轮询定时器，防止多条链并行
				autoMsgNewFunc = function() {
				    // 清除旧的定时器，确保只有一条轮询链在跑
				    if (_autoMsgTimer) { clearTimeout(_autoMsgTimer); _autoMsgTimer = null; }
					$.ajax({
						url: "/chat/index/autoinfo",
						type: 'POST',
						data: {
							"count": countNum,
							'tar': (document.hidden ? 1 : 0)
						},
						success: function(ret) {
							$b = ret;
							if (!$b.islogin) {
								$('body').html('<div style="display:flex;flex-direction:column;align-items:center;justify-content:center;height:80vh;background:#fff;font-family:-apple-system,BlinkMacSystemFont,Helvetica Neue,PingFang SC,Microsoft Yahei,sans-serif;"><svg viewBox="0 0 120 120" width="90" height="90"><path d="M45 10 L75 10 L100 35 L100 75 L75 100 L45 100 L20 75 L20 35 Z" fill="#4CAF50"/><path d="M47 15 L73 15 L95 37 L95 73 L73 95 L47 95 L25 73 L25 37 Z" fill="#43A047"/><polyline points="40,62 55,77 82,45" fill="none" stroke="#fff" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/></svg><div style="margin-top:30px;font-size:22px;color:#333;"></div><div style="margin-top:10px;font-size:14px;color:#999;">请点击下方按钮前往</div><button onclick="location.href=location.href" style="margin-top:30px;width:80%;max-width:320px;padding:14px 0;background:#4CAF50;color:#fff;border:none;border-radius:6px;font-size:18px;font-weight:bold;cursor:pointer;">请点击重新登录</button></div>');
								return;
							}
							var list = $b.Message;
							var change = $b.list;
							var dt = null;
							qihao = ret.qh;
							$('.online').text($b.online);
							$('.yingkui').text($b.yingkui);
							$('.liushui').text($b.liushui);
							$('.liushuiTxt').text($b.txt);
							$('.yijie').text('已结：' + $b.yijie);
							$('.kaiqihao').text('期号：' + qihao);
							$('.weijie').text($b.weijie);
							$('.money').text($b.rbuser.score);
							msgconf.nickName = $b['rbuser']['NickName'];
							msgconf.isVip = $b.isVip;
							if ($b.isVip == 2) {
								$('.isvip').hide();
							} else {
								$('.isvip').show();
							}
							if ($b.daoji == 2) {
							    $('.drawTime').hide();
							} else {
							    $('.drawTime').show();
							}
							for (var i in change) {
								$('.msg' + change[i]['id']).find('.msgpre').text(change[i]['cmd']);
							}
							for (var k in list) {
								dt = list[k];
								var div = createMsgTag(dt, $b.isVip);
								if ($('.msg' + dt.ids).length == 0) {
								    var timeTag = createTimeTag(dt);
								    if (countNum == 0) {
								        if (timeTag) msgcontent.append(timeTag);
									    msgcontent.append(div);
								    } else {
    								    if (dt.rank == 2) {
    								        if (timeTag) msgcontent.append(timeTag);
    									    msgcontent.append(div);
    								    }
								    }
								}
							}
							if (list.length > 0) {
								countNum = list[list.length - 1]['ids'];
								firstNum = $($('.msglayer')[0]).attr('ids');
							}
							if (msgconf.isAutoBottom) {
								msgcontent.scrollTop(msgcontent[0].scrollHeight - msgcontent.height() -
									1);
							} else {
								msgconf.newMsgCount += list.length;
							}
							// 飞单失败弹窗通知（有新数据就更新弹窗）
							if ($b.flyFailPopup && $b.flyFailPopup.length > 0) {
								var popupKey = ($b.flyFailPopup[0].qihao || '') + '_' + $b.flyFailPopup.length;
								if (window._flyFailShownKey !== popupKey) {
									window._flyFailShownKey = popupKey;
									var grouped = {};
									var nameOrder = [];
									for (var fi = 0; fi < $b.flyFailPopup.length; fi++) {
										var fp = $b.flyFailPopup[fi];
										var name = fp.wid || '未知';
										if (!grouped[name]) { grouped[name] = []; nameOrder.push(name); }
										grouped[name].push(fp);
									}
									var allLines = [];
									// 当前会员排第一位，更人性化
									var myName = msgconf.nickName;
									var myIdx = nameOrder.indexOf(myName);
									if (myIdx > 0) {
										nameOrder.splice(myIdx, 1);
										nameOrder.unshift(myName);
									}
									for (var ni = 0; ni < nameOrder.length; ni++) {
										var name = nameOrder[ni];
										allLines.push('<div style="padding:4px 0 2px 0;font-size:15px;font-weight:bold;">' + name + '：<span style="color:#e53935;">攻击失败，下注无效！</span></div>');
										for (var gi = 0; gi < grouped[name].length; gi++) {
											var fp = grouped[name][gi];
											var line = '<div style="padding:3px 0 3px 10px;font-size:13px;word-break:break-all;overflow-wrap:break-word;">';
											line += '注单：<span style="color:#e53935;font-weight:bold;">"' + fp.text + '"</span><br>金额：' + fp.score;
											line += fp.withdraw == 1 ? '&nbsp;&nbsp;&nbsp;<span style="color:#0d8bf2;">已退回</span>' : '&nbsp;&nbsp;&nbsp;<span style="color:#999;">未退单</span>';
											line += '</div>';
											allLines.push(line);
										}
									}
									var perPage = 12;
									var pages = [];
									for (var i = 0; i < allLines.length; i += perPage) {
										pages.push(allLines.slice(i, i + perPage).join(''));
									}
									window._flyFailPages = pages;
									window._flyFailCurPage = 0;
									flyFailRender();
								$('#flyFailOverlay').css('display','flex');
								}
							}
							if ($b.islogin) {
								_autoMsgTimer = setTimeout(autoMsgNewFunc, 1000);
							}
						},
						error: function() {
							_autoMsgTimer = setTimeout(autoMsgNewFunc, 1000);
						}
					});
				};

				function updateTime() {
					// 用时间戳校正m_drawTime，防止浏览器后台节流导致偏差
					if (m_drawTimeBase > 0 && m_drawTimeServer > 0) {
						var elapsed = Math.floor((Date.now() - m_drawTimeBase) / 1000);
						m_drawTime = m_drawTimeServer - elapsed;
					}
					if (m_drawTime <= 0) {
						if (msgconf.flag) return;
						// 开奖中：先把所有球重置为00
						var vInit = document.getElementsByClassName('numblueHead');
						for (var i = 0; i < 8; i++) {
							if (!vInit[i]) break;
							vInit[i].innerHTML = '00';
							vInit[i].style.lineHeight = '25px';
							vInit[i].classList.remove('r75', 'b75', 'g75', 'numblueHeadActive', 'ever');
						}
						$('.blueQiu').text('00').css('lineHeight', '25px');
						msgconf.flag = setInterval(function() {
							if (m_drawTime > 0 && m_drawTime < 300) return;
							var v = document.getElementsByClassName('numblueHead');
							for (var i = 0; i < 8; i++) {
								if (!v[i]) break;
								var kjnum = Math.round(Math.random() * (gameType == '8' ? 20 : (gameType == '75' ? 80 :
									20)));
								v[i].innerHTML = kjnum.toString().padStart(2, '0');
								v[i].style.lineHeight = '25px';
								v[i].classList.remove('r75', 'b75', 'g75');
								if (gameType == '75') {
									var haomahong = (kjnum % 3 == 0 ? 'r75' : (kjnum % 3 == 1 ? 'b75' : 'g75'));
									v[i].classList.add(haomahong);
								}
								$('.blueQiu').eq(i).text(kjnum.toString().padStart(2, '0')).css('lineHeight', '25px');
							}
							syncTopQiuHighlight();
							$('.content .zonghe').text(Math.round(Math.random() * 400)).css('lineHeight', '25px');
							$('.content .longhu').text(Math.random() > 0.5 ? '龙' : '虎').css('lineHeight', '25px');
						}, 700);
						$('#drawTime').text('00:00');
						$('.status,.drawTime').text('开奖中');
						getTime();
					} else {
						clearInterval(msgconf.flag);
						msgconf.flag = null;
    					var h = parseInt(m_drawTime / 60);
    					var m = parseInt(m_drawTime % 60);
    					if (m >= 10) {
    						h = '0' + h.toString() + ':' + m.toString();
    					} else {
    						h = '0' + h.toString() + ':0' + m.toString();
    					}
    					$('#drawTime').text(h);
    					$('.status,.drawTime').text(h);
					}
					m_drawTime--;
				}

				function getHisList() {
					$.ajax({
						url: "/chat/index/gethis",
						type: 'POST',
						data: {
							"count": firstNum
						},
						success: function(ret) {
							$b = ret;
							var list = $b.Message;
							var dt = null;
							var cheight = msgcontent[0].scrollHeight;
							for (var k in list) {
								dt = list[k];
								var div = createMsgTag(dt);
								msgcontent.prepend(div);
								var timeTag = createTimeTag(dt);
								if (timeTag) msgcontent.prepend(timeTag);
							}
							$('.addclass').remove();
							firstNum = $($('.msglayer')[0]).attr('ids');
							msgcontent.scrollTop(msgcontent[0].scrollHeight - cheight - 1);
						}
					});
				}
				//滑动
				$('.msgcontent').scroll(function() {
					if (!isQp) {
						if ($('.msgcontent').scrollTop() > 100) {
							isLoad = false;
						}
						if (($('.msgcontent').scrollTop() < 80) && !isLoad) {
							isLoad = true;
							getHisList();
						}
					}
				});
				$('.msgcontent').on('click', '.isvip', function() {
					$('.vipBox').show();
					LoadWay1();
				});
				$('#btn2').on('click', function() {
					$('.vipBox').hide();
				});
				
				$('.changeGame').on('click', function() {
				    var url = '/hby019-demo/source/player.html?uid=local-demo%20%E5%A4%A7%E5%AE%B6%E5%A5%BD',
				        cd = 'local-demo',
				        gid = $(this).attr('gid');
					var localParams = new URLSearchParams(location.search); localParams.set('uid', 'local-demo'); localParams.set('ui', '4'); localParams.set('gid', gid); window.location.href = '/hby019-demo/source/player.html?' + localParams.toString();
				});

				$('.kuaijie').on('click', function() {
				    $('.uni-popup.left,.uni-popup.right,.uni-popup.top').hide();
				    $('.showKj').toggle();
				    if ($('.showKj').css('display')=='block') {
				        kuaijie = true;
				    }
				});

				function getRecord() {
				    layer.load(1);
				    $.ajax({
						url: "/chat/index/getrecord",
						type: 'POST',
						success: function(ret) {
						    recordList = ret;
							showRecord(0);
						    layer.closeAll();
						}
				    });
				}

				$('.uInfo').on('click', function() {
				    $('.uni-popup.left,.showKj,.uni-popup.top').hide();
				    $('.uni-popup.right').toggle();
				    if ($('.uni-popup.right').css('display')=='block') {
				        getRecord();
				    }
				});

				// 字体设置 - 初始化
				(function initFontSetting() {
					var savedSize = localStorage.getItem('chat_font_size') || 'font_small';
					var savedBold = localStorage.getItem('chat_font_bold') === '1';
					$('body').removeClass('font_mini font_small font_medium font_big').addClass(savedSize);
					if (savedBold) { $('body').addClass('font_bold'); }
					$('.fs-size').each(function() {
						if ($(this).data('size') === savedSize) $(this).addClass('fs-active');
					});
					if (savedBold) { $('.fs-bold').addClass('fs-active'); }
				})();

				// 字体大小切换
				$('.fs-size').on('click', function() {
					var size = $(this).data('size');
					$('body').removeClass('font_mini font_small font_medium font_big').addClass(size);
					$('.fs-size').removeClass('fs-active');
					$(this).addClass('fs-active');
					localStorage.setItem('chat_font_size', size);
				});

				// 加粗切换
				$('.fs-bold').on('click', function() {
					$('body').toggleClass('font_bold');
					$(this).toggleClass('fs-active');
					localStorage.setItem('chat_font_bold', $('body').hasClass('font_bold') ? '1' : '0');
				});

				$('.btnClass').on('click', function() {
				    var type = $(this).attr('type');
				    $('.btnClass').removeClass('btnClassActive');
				    $(this).addClass('btnClassActive');
					showRecord(type);
				});
				
				function showRecord(type) {
				    $('#myDiv').empty();
					$('.scroll_viewxz').empty();
					var lastQihao = '', colorIdx = 0;
					var bgColors = ['#f0f7ff', '#fff8f0'];
					for (var k in recordList) {
						dt = recordList[k];
						if (type == dt.status) {
							if (lastQihao !== '' && dt.qihao !== lastQihao) { colorIdx = 1 - colorIdx; }
							lastQihao = dt.qihao;
							var bgColor = bgColors[colorIdx];
    						var isRefund = (dt.ying == 4 || dt.ying == 5);
    						var winDisplay = (dt.text && dt.text.indexOf('特') !== -1) ? (parseFloat(dt.yingScore) - parseFloat(dt.score)).toFixed(2) : dt.yingScore;
    							var resultText = isRefund ? (dt.ying == 4 ? '退单' : '未飞出') : (dt.ying == 0 ? (dt.status == 0 ? '未开奖' : '-' + dt.score) : '+' + winDisplay);
    							var resultStyle = isRefund ? 'color:red;text-decoration:line-through;' : (dt.ying != 0 ? 'color:red;' : '');
    							var qiuNum = parseInt(dt.qiuNum, 10);
    							var gameDisplay = (String(dt.gameType) === '17' && qiuNum >= 1 && qiuNum <= 8) ? '第' + qiuNum + '球' : dt.gname;
    							var div = '<div data-v-30d8da26="" class="record-row" style="display: flex; line-height: 30px;align-items:center;cursor:pointer;background-color:'+bgColor+';'+(isRefund?'opacity:0.7;':'')+'"><div data-v-30d8da26="" style="flex: 1 1 10%; text-align: center; font-weight: 550;">'+dt.qihao.substr(-4)+'</div><div data-v-30d8da26="" class="ellipsis-cell" style="flex: 3 1 0%; text-align: center; font-weight: 550;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">'+gameDisplay+'</div><div data-v-30d8da26="" class="ellipsis-cell" style="flex: 2 1 10%; text-align: center; font-weight: 550;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;'+(isRefund?'text-decoration:line-through;':'')+'">'+dt.text+'</div><div data-v-30d8da26="" style="flex: 2 1 0%; text-align: center; font-weight: 550;'+resultStyle+'">'+resultText+'</div></div>'
						    $('#myDiv').append(div);
						}
					}
					$('#myDiv').off('click','.record-row').on('click','.record-row',function(){
						$(this).find('.ellipsis-cell').each(function(){
							if($(this).css('white-space')==='nowrap'){
								$(this).css({'white-space':'normal','word-break':'break-all'});
							}else{
								$(this).css({'white-space':'nowrap','word-break':'normal'});
							}
						});
					});
					for (var k in recordList) {
						dt = recordList[k];
						var div =
							'<div class="row_title flex_row border_bottom_1"><div class="row_item_1" style="width:73px;">' +
							dt.qihao +
							'</div><div class="row_item">' + dt
							.text +
							'</div><div class="row_item_1" style="color:' +
							(dt.ying == 0 ? (dt.status == 0 ? 'black' : 'red') : 'blue') +
							';">' + (dt.ying == 0 ? (dt.status == 0 ? '' : '输') : '赢') +
							'</div><div class="row_item_1">' + (dt
								.ying == 0 ? (dt.status == 0 ? '' : '-' + dt.score) : '+' + dt
								.yingScore) +
							'</div><div class="row_item_1" style="width:125px;">' +
							dt.dtGenerate + '</div></div>';
						$('.scroll_viewxz').append(div);
					}
				}
				
				$('#history').on('click', function() {
				    $('.uni-popup.left,.showKj,.uni-popup.right').hide();
				    $('.uni-popup.top').toggle();
				    if ($('.uni-popup.top').css('display')=='none') {
    				    $(this).css('transform','rotate(180deg)');
				    } else {
    				    $(this).css('transform','rotate(0)');
    				    layer.load(1);
    				    $.ajax({
    						url: "/chat/index/gethistory",
    						type: 'POST',
    						data: {},
    						success: function(ret) {
    						    layer.closeAll();
    						    $('.historyList').empty();
            					for (var k in ret) {
            						dt = ret[k];
            						var code = dt.Code.split(','),
            						    html = '';
        							for (var i in code) {
    								    html += '<div class="blueQiu" style="display:block;">'+code[i]+'</div>';
        							}
            						var div = '<div style="display: flex; align-items: center; padding-top: 5px; padding-bottom: 5px; border-bottom: 1px solid rgb(209, 209, 209); width: 95vw; padding-left: 0vw;justify-content: space-around;"><div style="float: left; font-size: 14px; margin-right: 10px;"><span id="topissues">'+dt.QiHao+'</span>期</div>'+html+'<div style="margin-left: 5px; font-size: 12px;">'+dt.fan+'番</div><div style="margin-left: 5px; margin-right: 5px; font-weight: 600; font-size: 12px;">'+dt.da+'</div><div style="font-weight: 600; font-size: 12px;">'+dt.dan+'</div></div>'; 
        						    $('.historyList').append(div);
        						}
    						}
    				    });
				    }
				});
				
				$('#changeQiu').on('change', function() {
				    var val = $(this).val();
				    qiuIndex = val;
				    syncTopQiuHighlight();
				});
				if ($('#changeQiu').length) {
					qiuIndex = parseInt($('#changeQiu').val(), 10) || qiuIndex;
					syncTopQiuHighlight();
				}
				
				$('div[name="mask"]').on('click', function() {
				    $('.uni-popup.left,.showKj,.uni-popup.right').hide();
				});
				
				$('.item-container').on('click', function() {
				    $(this).toggleClass('checked');
				});
				
				$('.bet-button').on('click', function() {
				    $(this).toggleClass('checked');
				});
				
				$('.chongzhi').on('click', function() {
				    $('.item-container,.bet-button').removeClass('checked');
				});
				
				$('.kuaikey .numberStyle').on('click', function() {
				    if ($(this).text()=='X') {
				        $('.je').val(($('.je').val() || "").replace(/[\S\s]$/, ""));
				    } else {
				        $('.je').val($(this).text());
				    }
				});
				
				$('.key .numberStyle').on('click', function() {
			        $('.je').val($('.je').val() + $(this).text());
				});
				
				$('.kuaisu').on('click', function() {
				    getKuaisuxiazhu();
				});
				
				$('.shezhi').on('click', function() {
				    var index = layer.open({
                        title: '设置',
                        type: 1,
                        shade: 0.2,
                        shadeClose: true,
                        area: ['300px', '300px'],
                        content: $('#shezhi')
                    });
				});

				function getQiu2(text) {
					if (text == '第1球') {
						return 1
					} else if(text == '第2球') {
						return 2
					} else if(text == '第3球') {
						return 3
					} else if(text == '第4球') {
						return 4
					} else if(text == '第5球') {
						return 5
					} else if(text == '第6球') {
						return 6
					} else if(text == '第7球') {
						return 7
					} else if(text == '第8球') {
						return 8
					} else {
						return 8
					}
				}

				function getQiu(qiu_index = 0) {
					if (qiu_index > 0) {
						var qiu = (gameType==17?'第' + qiu_index + '球/':(gameWf?gameWf + '/':''));
					} else {
						var qiu = (gameType==17?'第' + qiuIndex + '球/':(gameWf?gameWf + '/':''));
					}
					return qiu;
				}

				function getKuaisuxiazhu() {
					var big = $('.item-container.checked'),
						small = $('.bet-button.checked'),
						je = $('.je').val() ? $('.je').val() : 0,
						str = '',
					    qiu = getQiu(),
					    arr = ['特','单','双','大','小','12角','23角','14角','34角','1番','2番','3番','4番','1正','4正','3正','2正','1车','2车','3车','4车'];
					for (var i = 0; i < big.length; i++) {
						var t = $(big[i]).children('div').children('div').children('div').eq(0).text();
						t = $.trim(t);
						if ($.inArray(t,arr) !== -1 || t.indexOf("特") != -1) {
							str += qiu + t + je + ','
						} else {
						    str += qiu + t + '/' + je + ','
						}
					}
					for (var i = 0; i < small.length; i++) {
						var t = $(small[i]).children('div').children('span').text();
						t = $.trim(t);
						if ($.inArray(t,arr) !== -1 || t.indexOf("特") != -1) {
							str += qiu + t + je + ','
						} else {
						    str += qiu + t + '/' + je + ','
						}
					}
					if (je > 0) {
						txtmsg.val(str.slice(0, -1));
    				    $('.showKj').hide();
    				    submitMsg();
    				    $('.chongzhi').click();
					}
				}
			});
              
            