# Guía para desarrollar un plugin

Un plugin es un **bundle OSGi con uno o más componentes Declarative Services** que implementan un contrato de Atlas.

## 1. Estructura mínima

```
mi-plugin/
├── pom.xml
└── src/main/java/com/miorg/plugin/MiImplementacion.java
```

`pom.xml` (hereda de `atlas-hybrid-parent`):

```xml
<dependencies>
    <dependency>                                  <!-- el contrato: SIEMPRE provided -->
        <groupId>org.atlas.hybrid</groupId>
        <artifactId>atlas-hybrid-greeting-api</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.osgi</groupId>
        <artifactId>org.osgi.service.component.annotations</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>                                  <!-- solo si se usa BundleContext -->
        <groupId>org.osgi</groupId>
        <artifactId>osgi.core</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>biz.aQute.bnd</groupId>
            <artifactId>bnd-maven-plugin</artifactId>   <!-- genera MANIFEST.MF y el XML de DS -->
        </plugin>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-jar-plugin</artifactId>
            <configuration>
                <archive>
                    <manifestFile>${project.build.outputDirectory}/META-INF/MANIFEST.MF</manifestFile>
                </archive>
            </configuration>
        </plugin>
    </plugins>
</build>
```

## 2. El componente

```java
@Component(service = GreetingService.class, property = {
    "atlas.plugin.id=greeting-fr",                 // identificador estable del plugin
    Constants.SERVICE_RANKING + ":Integer=15" })   // prioridad frente a otros plugins
public class FrenchGreetingService implements GreetingService {

    @Override
    public Greeting greet(String name) {
        return new Greeting("Bonjour, " + name + " !", "greeting-fr", "1.0.0");
    }
}
```

## 3. Reglas

1. **Implementa solo contratos publicados.** No exportes paquetes de implementación: bnd no los exporta salvo que se pida.
2. **Sin estado compartido global.** El plugin puede detenerse en cualquier momento. Libera los recursos en `@Deactivate`.
3. **Thread-safe.** Varias peticiones HTTP llaman al mismo objeto de servicio a la vez.
4. **Excepciones de negocio claras.** Llegan sin envolver a la aplicación (ADR-006).
5. **`atlas.plugin.id` obligatorio y estable.** Las aplicaciones pueden filtrar por él: `@OsgiService(filter = "(atlas.plugin.id=greeting-fr)")`.
6. **Nada de HK2 ni de clases internas de GlassFish** (ADR-002).
7. **Dependencias de terceros:** van embebidas y privadas (sección 4) o como bundles compartidos desplegados aparte. Nunca se exportan desde un plugin.
8. **Persistencia:** a través de los recursos JDBC del servidor, dentro de la transacción del llamante (sección 5 y ADR-007).

## 4. Empaquetar las dependencias del plugin

Un plugin puede llevar **dentro** sus librerías, privadas a él. Así, dos plugins pueden usar versiones distintas de la misma librería sin conflicto, y nadie más tiene que desplegarlas.

### 4.1 Opción recomendada: `-conditionalpackage` (validada en la PoC)

bnd copia dentro del bundle **solo los paquetes alcanzables desde tu código** (de forma transitiva) que coincidan con el patrón. El plugin `atlas-hybrid-plugin-en` lo usa con `commons-text` (y su dependencia `commons-lang3`):

```xml
<dependency>                                   <!-- scope compile: bnd la necesita en el classpath -->
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-text</artifactId>
    <version>1.13.0</version>
</dependency>
...
<plugin>
    <groupId>biz.aQute.bnd</groupId>
    <artifactId>bnd-maven-plugin</artifactId>
    <configuration>
        <bnd><![CDATA[
-conditionalpackage: org.apache.commons.text.*, org.apache.commons.lang3.*
]]></bnd>
    </configuration>
</plugin>
```

Resultado verificado en el manifiesto:
- los paquetes `org.apache.commons.*` aparecen en `Private-Package`, **no** en `Export-Package`;
- `Import-Package` solo contiene paquetes del JDK, el contrato y `org.osgi.framework`;
- en GlassFish funciona sin ningún bundle `commons-*` desplegado (`demo.sh`: *"embedded commons-text (WordUtils) inside the plugin"*).

**Revisa siempre `Import-Package` del bundle generado** (`unzip -p plugin.jar META-INF/MANIFEST.MF`). Si aparecen paquetes de la librería que no deberían, amplía el patrón. Si aparecen dependencias opcionales que no usas, márcalas como opcionales en el `bnd`: `Import-Package: com.opcional.*;resolution:=optional, *`.

### 4.2 Alternativa: JAR completo en `Bundle-ClassPath` (no probada en la PoC)

Útil para librerías que cargan recursos o clases por reflexión y no se llevan bien con la copia de paquetes:

```
-includeresource: lib/mi-libreria.jar=mi-libreria-*.jar;lib:=true
```

bnd añade el JAR a `Bundle-ClassPath`. El bundle crece con el JAR entero.

### 4.3 Cuándo **no** embeber

- Si **varios plugins y la aplicación** tienen que compartir los mismos objetos de esa librería (por ejemplo, tipos que aparecen en un contrato), debe ser un **bundle compartido** desplegado aparte y versionado, igual que un contrato (ADR-003).
- Las APIs de Jakarta EE, OSGi y del contrato **nunca** se embeben: siempre `scope provided`.

## 5. Persistencia

`@PersistenceContext`, `@PersistenceUnit`, `@Resource` y `@Transactional` **no funcionan en un `@Component`**: son anotaciones del contenedor Jakarta EE, y SCR no las procesa. Lo validado (ADR-007, findings H-6) es esto:

```java
@Component(service = OrderPricing.class, property = "atlas.plugin.id=pricing-v2")
public class PricingV2 implements OrderPricing {

    private volatile DataSource dataSource;

    @Override
    public Price price(Order order) {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT factor FROM pricing WHERE sku = ?")) {
            ps.setString(1, order.sku());
            ...                           // participa en la transacción JTA del llamante
        } catch (SQLException e) {
            throw new PricingException("Pricing lookup failed", e);
        }
    }

    /** Lookup perezoso: los pools de GlassFish se inicializan bajo demanda; nunca en @Activate. */
    private DataSource dataSource() throws NamingException {
        DataSource ds = dataSource;
        if (ds == null) {
            ds = (DataSource) new InitialContext().lookup("jdbc/pricing");
            dataSource = ds;
        }
        return ds;
    }
}
```

- La aplicación demarca la transacción (`@Transactional`). Si hace rollback, las escrituras del plugin también se deshacen (comprobado).
- El plugin no inicia ni confirma transacciones globales.
- Si las entidades JPA son de la aplicación, quedan en la aplicación, y el plugin trabaja con DTOs del contrato.
- JPA dentro del plugin con EclipseLink está **pendiente de validar** (hoja de ruta).

## 6. Probar

1. Tests unitarios: el componente es un POJO, se instancia directamente.
2. Integración: `asadmin deploy --type=osgi --name=greeting-fr target/mi-plugin.jar` y después `GET /atlas/api/greetings/plugins`.
3. Comprueba que retirar el plugin (`asadmin undeploy greeting-fr`) no deja trazas de error en `server.log`.

## 7. Consumirlo desde una aplicación

```java
@Inject @OsgiService
GreetingService greetings;          // el de mayor ranking

@Inject @PluginId("greeting-fr")
GreetingService french;             // uno concreto, por su atlas.plugin.id

@Inject @Any
Instance<GreetingService> byId;     // elegido en tiempo de ejecución; seguro con ids del usuario
GreetingService chosen = byId.select(PluginId.Literal.of(id)).get();
```

El JAR `atlas-hybrid-bridge` va en `WEB-INF/lib` (scope `compile`) y el contrato con `scope provided`. Para traducir `ServiceUnavailableException` a HTTP 503, ver `ServiceUnavailableMapper` en la demo.
