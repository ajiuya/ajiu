export default {
  brand: "Ajiu Workbench",
  meta: {
    title: "Ajiu Workbench — AI Development Workspace for Android",
    description: "Ajiu Workbench is an independent Android AI development workspace based on open-source AiCode. Includes Linux containers, remote SSH, terminals, Git and plugins, with user approval and separate planning. Not the official AiCode website."
  },
  nav: {
    logoAlt: "Ajiu Workbench logo",
    label: "Main navigation",
    links: {
      preview: "Preview",
      features: "Features",
      download: "Quick start",
      sponsors: "Upstream information",
      docs: "Upstream docs (Chinese)",
      changelog: "Upstream changelog"
    },
    lightMode: "Switch to light mode",
    darkMode: "Switch to dark mode",
    github: "Upstream AiCode repository",
    openMenu: "Open navigation menu",
    closeMenu: "Close navigation menu",
    language: {
      desktop: "中文",
      mobile: "中文",
      label: "Switch to Chinese",
      title: "Switch to Chinese"
    }
  },
  hero: {
    subtitle: "A Linux development workspace for your phone and tablet. Independently adapted from AiCode, with user approval and separate planning. Not affiliated with the upstream project.",
    download: "Test package information",
    docs: "Upstream docs (Chinese)"
  },
  features: {
    title: "Features",
    subtitle: "An AI Agent and development environment built for real software development on mobile",
    items: {
      agent: {
        title: "AI Agent & built-in browser",
        desc: "Built-in file reading, writing and editing, Shell execution, background terminals, code and web search, image recognition, task lists and a multi-tab WebView browser. Spawn sub-agents with independent context for parallel background research."
      },
      authorization: {
        title: "User approval & separate planning",
        desc: "Sensitive actions follow regular authorization rules. Plans are managed separately. Plugins can request a native confirmation but cannot approve on your behalf. Checkpoints let you roll back files and conversations."
      },
      terminal: {
        title: "Built-in terminal & Linux containers",
        desc: "Local Linux containers powered by Termux and PRoot, with a built-in Alpine image, custom rootfs imports and host directory mounts. Terminals support multiple tabs and persistent background sessions."
      },
      remote: {
        title: "Remote SSH & Shizuku backends",
        desc: "Use a remote SSH server as the execution backend, with commands, files and terminals operating directly on remote projects. Shizuku also lets you run shell and adb debugging commands on the host Android system."
      },
      editor: {
        title: "Code editor, Git & sync",
        desc: "Open files from an indented tree in a full-screen editor with syntax highlighting, or use a side-by-side layout on tablets. Visual Git tools cover status, branches, commit history and diffs, alongside SFTP / FTP sync and a built-in FTP server."
      },
      models: {
        title: "Model-agnostic, MCP, Skills & memory",
        desc: "Compatible with OpenAI / Anthropic / Gemini protocols and custom providers, with automatic rotation across multiple API Keys and Token cost tracking. Connect local or remote MCP servers, use global or project-level Skills, and keep long-term memory."
      }
    }
  },
  download: {
    title: "Quick start",
    subtitle: "Start developing in three steps, with no computer or extra setup required.",
    android: "Android 8.0+",
    api: "(API 26)",
    steps: {
      install: {
        title: "Receive a test APK in chat",
        desc: "Test packages are shared in the current chat. Verify the version, signature and checksum before installing. This site offers no public download or one-click installer."
      },
      provider: {
        title: "Set up an AI provider",
        desc: "In Settings → AI providers, choose a built-in preset or a custom endpoint, enter your own API Key and enable a model."
      },
      environment: {
        title: "Choose an environment and start chatting",
        desc: "The built-in Alpine container is used by default. You can also switch to a remote SSH server in Containers & images, then start a new chat to begin development."
      }
    },
    packageTitle: "Test packages are shared in chat",
    packageDescription: "Ajiu Workbench has no public release channel. Ask for a verified test package in the current chat. Upstream links are references, not Ajiu downloads.",
    github: "Explore features",
    mirror: "Upstream guide (Chinese)"
  },
  sponsors: {
    title: "Upstream project information",
    subtitle: "These services and original links are retained from the upstream AiCode website for attribution only. They do not imply sponsorship, endorsement or a relationship with Ajiu Workbench.",
    items: {
      rainyun: {
        name: "RainYun",
        tag: "Listed on the upstream website",
        desc: "A cloud service listed by the upstream project. Check the service provider for current products, prices and offers. Ajiu Workbench claims no commercial relationship.",
        linkText: "Visit RainYun"
      },
      qiniu: {
        name: "Qiniu AI",
        tag: "Original upstream referral link",
        desc: "An AI service referral link retained from the upstream website. Check the service provider for current token allowances, terms and model support. This is not a commercial promise from Ajiu.",
        linkText: "Learn more"
      }
    }
  },
  cta: {
    title: "Start your next project on your phone",
    subtitle: "Ajiu Workbench is an independent adaptation. Thanks to AiCode and its open-source contributors. Test packages are shared in the current chat.",
    download: "Test package information",
    github: "View upstream source"
  },
  footer: {
    backToTop: "Ajiu Workbench, back to top",
    description: "An independent adaptation of AiCode for local and remote development. Not the official AiCode website. Retains upstream licenses and attribution.",
    navigation: "Footer navigation",
    columns: {
      quickLinks: {
        title: "Quick links",
        links: {
          releases: "Upstream releases",
          download: "Ajiu test package information",
          guide: "Upstream guide (Chinese)",
          quickStart: "Upstream quick start (Chinese)"
        }
      },
      contribute: {
        title: "Upstream feedback & contributions",
        links: {
          issues: "Issues & suggestions",
          pulls: "Contribute code"
        }
      },
      credits: {
        title: "Acknowledgments",
        links: {
          opencode: "OpenCode",
          termux: "Termux",
          kelivo: "Kelivo"
        }
      }
    },
    copyright: "Based on © 2026 AiCode · GPL-3.0; plugin SDK retains its MIT license and author attribution",
    usage: "For learning, research and lawful software development only"
  }
};
