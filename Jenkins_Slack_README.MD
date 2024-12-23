**Introduction**


Main pipeline is slacking build and hw test failures to project own build results channel. OK builds are left out messaging intentionally

Slack operations requires first Slack App creation and configuration followed
by actual usage , in this case via Jenkins plugin

**Slack configurations**

NOTE: If you allready have "write app" in use, you can use it instead of creating new one

1) Create new Slack app in https://api.slack.com/apps
- Select Crate New App / from Sratch
- Name your app and select workspace to be added

2) App permissions
-Select left side menu: OAuth & Permissions
-Select Add an OAuth Scope (under subtitle Scopes), then select chat:write authorization permission
- Select Install to Workspace, check that permissions are ok and select Allow
- Take copy of the generated secret OAuth Token!!!!

3) Add app to Slack workspace and channel

- Under Apps menu (left side in the Slack app), Select Add apps and select this created app
- App is now visible in the Slack App menu
- Go to the channel wished to be used. From upper side of the app, select Channel info / Integrations / Add apps and select you just created app name


**Jenkins Configurations**

1) Used channel is defined in ghaf-main-pipeline.groovy (as serverchannel="ghaf-build"), change as needed. Gathering "failures" information happens in utility library utils.groovy and actual actions
in pipeline post/failure phase.


2) Jenkins Slack Notifification plugin is in use. Plugin has been added via Nix configurations and happens during Azure/Terraform deployments. However, currently one needs to configure Slack workspace and secret token once for every new Jenkins variant going to need to use slacking. This may change in future due configuration of code plugin usage.

Here are steps for Jenkins configurations:

1) Jenkins dashboard
2) Click Manage Jenkins
3) Click Configure System
4) Scroll down to Slack section
5) Set up your Slack workspace details and secret token.
6) Click the "Custom Slack app bot user"
7) Use your build/test channel from configruation UI to test slacking works


