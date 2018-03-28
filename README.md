<p align="center">
  <a href="http://mobbeel.com">
    <img src="http://www.mobbeel.com/wp-content/uploads/2015/03/mobbeel_logo_transparente.png" width="350px">
  </a>
  <center><h1>Mobbeel fat AAR Gradle plugin</h1></center>
</p>

![license](https://img.shields.io/hexpm/l/plug.svg)

Gradle script that allows you to merge and embed dependencies in generated AAR file on library projects.

### How to use?

1. Configure your buildscript to include the plugin:

  ```
  buildscript {
    repositories {
      maven { url "https://plugins.gradle.org/m2/" }
    }
    dependencies {
      classpath "gradle.plugin.com.mobbeel.plugin:mobbeel-fataar:1.0.0"
    }
  }
  ```

2. Apply the plugin on the top of your library module **build.gradle**:

  ```
  apply plugin: "com.mobbeel.plugin"
  ```

3. Mark with '**api**' configuration dependecies that need embed on the final AAR:

  ```
  dependencies {
      api "com.mobbeel:my-lib:1.0.0"  // <- Embed external dependency from any repository
      api project(':My-lib-module')   // <- Embed internal dependency on workspace

      ...
  }
  ```
