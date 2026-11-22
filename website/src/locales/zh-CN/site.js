export default {
  brand: "阿九工作台",
  meta: {
    title: "阿九工作台 — Android 上的 AI 开发工作台",
    description: "阿九工作台，基于开源 AiCode 独立修改的 Android AI 开发工作台。支持 Linux 容器、远程 SSH、终端、Git 与插件，采用普通授权与独立计划。本页面不代表 AiCode 官方。"
  },
  nav: {
    logoAlt: "阿九工作台标志",
    label: "主导航",
    links: {
      preview: "界面预览",
      features: "功能特性",
      download: "快速开始",
      sponsors: "上游项目信息",
      docs: "上游参考文档",
      changelog: "上游更新日志"
    },
    lightMode: "切换浅色模式",
    darkMode: "切换深色模式",
    github: "上游 AiCode 仓库",
    openMenu: "打开导航菜单",
    closeMenu: "关闭导航菜单",
    language: {
      desktop: "EN",
      mobile: "English",
      label: "切换为英文",
      title: "切换为英文"
    }
  },
  hero: {
    subtitle: "把 Linux 开发工作台装进手机与平板。基于 AiCode 独立修改，保留本人授权与独立计划；不代表上游官方。",
    download: "获取测试包说明",
    docs: "查看上游参考文档"
  },
  features: {
    title: "功能特性",
    subtitle: "围绕移动端真实软件开发打造的 AI Agent 与开发环境",
    items: {
      agent: {
        title: "AI Agent 与内置浏览器",
        desc: "内置文件读写与编辑、Shell 执行、后台终端、代码与网页搜索、图片识别、待办清单与多标签 WebView 浏览器；支持派生独立上下文的子代理在后台并行调研。"
      },
      authorization: {
        title: "本人授权与独立计划",
        desc: "按普通授权规则确认敏感操作，计划独立制定与查看；插件只能发起原生确认请求，不能代替本人批准。编辑前自动记录检查点，可按需回滚文件与对话。"
      },
      terminal: {
        title: "内置终端与 Linux 容器",
        desc: "基于 Termux 与 PRoot 的本地 Linux 容器，内置 Alpine 镜像，支持导入自定义 rootfs、挂载宿主目录；终端支持多标签与后台常驻运行。"
      },
      remote: {
        title: "远程 SSH 与 Shizuku 后端",
        desc: "可把远程 SSH 服务器作为执行后端，命令、文件与终端直接作用于远端项目；也支持借助 Shizuku 在宿主 Android 系统上执行 shell 与 adb 调试命令。"
      },
      editor: {
        title: "代码编辑器、Git 与同步",
        desc: "缩进式文件树点开即进全屏语法高亮编辑器，支持平板双栏并排显示；内置可视化 Git 管理状态、分支、提交历史与差异，支持 SFTP / FTP 同步与内置 FTP 服务端。"
      },
      models: {
        title: "模型无关、MCP 与技能记忆",
        desc: "兼容 OpenAI / Anthropic / Gemini 协议与自定义供应商，支持多 Key 自动轮换与 Token 费用统计；支持连接本地与远程 MCP 服务器、全局/项目级技能（Skills）与长期记忆。"
      }
    }
  },
  download: {
    title: "快速开始",
    subtitle: "无需电脑或额外环境准备，三步开启开发。",
    android: "Android 8.0+",
    api: "（API 26）",
    steps: {
      install: {
        title: "从聊天接收测试 APK",
        desc: "阿九测试包由当前聊天发放，请核对版本、签名与校验信息后安装；本站不提供公开下载或一键安装。"
      },
      provider: {
        title: "配置 AI 供应商",
        desc: "在「设置 → AI 供应商」选择内置预设或自定义端点，填入自己的 API Key 并启用模型。"
      },
      environment: {
        title: "选择环境，开始对话",
        desc: "默认使用内置 Alpine 本地容器；也可在「容器与镜像」切换远程 SSH 服务器，新建会话即可开始开发。"
      }
    },
    packageTitle: "测试包由聊天发放",
    packageDescription: "阿九工作台尚无公开发布渠道。请在当前聊天中索取已核验的测试包；上游链接仅供参考，不是阿九下载。",
    github: "了解功能",
    mirror: "上游使用指南"
  },
  sponsors: {
    title: "上游项目信息",
    subtitle: "以下服务与原链接来自上游 AiCode 网站，仅作为来源信息保留，不表示其赞助、推荐或认可阿九工作台。",
    items: {
      rainyun: {
        name: "雨云 RainYun",
        tag: "上游网站所列服务",
        desc: "上游网站列出的云服务商。具体产品、价格与优惠请以服务方当前说明为准，阿九工作台没有声明与其存在商业合作。",
        linkText: "访问雨云"
      },
      qiniu: {
        name: "七牛云 AI",
        tag: "上游原始推广链接",
        desc: "上游网站保留的 AI 服务推广链接。Token 额度、有效期与模型支持请以服务方当前说明为准，不构成阿九的商业承诺。",
        linkText: "了解详情"
      }
    }
  },
  cta: {
    title: "在手机上开启你的下一个项目",
    subtitle: "阿九工作台是独立修改版本；感谢 AiCode 及其开源贡献者。测试包请从当前聊天获取。",
    download: "查看测试包说明",
    github: "查看上游源码"
  },
  footer: {
    backToTop: "阿九工作台，返回顶部",
    description: "基于 AiCode 的独立修改版本，支持本地与远程开发；非 AiCode 官方网站。保留上游开源许可与署名。",
    navigation: "页脚导航",
    columns: {
      quickLinks: {
        title: "快速入口",
        links: {
          releases: "上游 Releases",
          download: "阿九测试包说明",
          guide: "上游使用指南",
          quickStart: "上游快速上手"
        }
      },
      contribute: {
        title: "上游反馈与贡献",
        links: {
          issues: "问题与建议",
          pulls: "贡献代码"
        }
      },
      credits: {
        title: "致谢项目",
        links: {
          opencode: "OpenCode",
          termux: "Termux",
          kelivo: "Kelivo"
        }
      }
    },
    copyright: "基于 © 2026 AiCode · GPL-3.0；插件 SDK 保留 MIT 许可与原作者署名",
    usage: "仅供学习、研究与合法的软件开发用途"
  }
};
