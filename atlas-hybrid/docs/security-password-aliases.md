# Seguridad de los password alias de GlassFish 8

- **Fecha:** 2026-10-26
- **Entorno de las pruebas:** Eclipse GlassFish 8.0.5, OpenJDK 21. El código revisado es el de la rama `main` (8.0.6-SNAPSHOT).
- **Alcance:** almacén de alias del dominio (`config/domain-passwords.p12`), master password y su fichero guardado (`master-password.p12`). Todas las pruebas se hicieron sobre dominios propios y desechables.

## 1. Cómo protege GlassFish los alias

| Elemento | Detalle (verificado en código y en disco) |
|---|---|
| Almacén | `config/domain-passwords.p12`, un keystore PKCS#12 (`PasswordAdapter`) |
| Cada alias | Una entrada `SecretKeyEntry`: los bytes de la contraseña guardados como "clave AES" |
| Clave de protección | **Solo la master password del dominio.** Protege el fichero (MAC) y cada entrada |
| Parámetros | MAC HMAC-SHA256 con **10 000 iteraciones** (salida de `openssl pkcs12 -info`); entradas `pkcs8ShroudedKeyBag` con el cifrado PKCS#12 por defecto del JDK |
| Master password por defecto | `changeit` (`KEYSTORE_PASSWORD_DEFAULT`). `start-domain` la **prueba primero** y, si funciona, no pide nada |
| Master password guardada (`--savemasterpassword`) | `<dominio>/master-password.p12`, protegido con la constante **`"master-password"`** fija en el código (`MASTER_PASSWORD_PASSWORD`) |

**Consecuencia:** la seguridad de todos los alias equivale exactamente a la de la master password, y a la del sitio donde esta se guarde.

## 2. ¿Qué pasa si alguien extrae `domain-passwords.p12`?

Se probó copiando únicamente ese fichero e intentando abrirlo fuera de GlassFish con un programa Java de 20 líneas (`KeyStore.getInstance("PKCS12")`).

| Situación del dominio | Resultado de la prueba | Riesgo |
|---|---|---|
| Master password por defecto `changeit` (el caso de una instalación sin endurecer, incluido el `domain1` de la distribución) | ❌ `atlas.leak-test -> Secreto-De-Prueba-123`: **todos los secretos en claro, al instante** | **Crítico** |
| El fichero va acompañado de `master-password.p12` (`--savemasterpassword`) | ❌ Abriendo `master-password.p12` con `"master-password"` sale la master password, y con ella todo | **Crítico**: el fichero solo ofusca, no cifra |
| Master password fuerte, no guardada en disco | ✅ Con otra contraseña: `Integrity check failed`. Solo queda la fuerza bruta offline | Depende de la fortaleza. Con 10 000 iteraciones de PBKDF2 (muy por debajo de lo recomendado hoy), una contraseña corta o de diccionario cae rápido. Con 20 o más caracteres aleatorios es inviable |
| Master password fuerte, pero el atacante también tiene el **proceso o la memoria** (volcado de heap, RCE) | No probado | La master password y los alias leídos están en memoria: el cifrado en disco no protege ante un compromiso del servidor en ejecución |

## 3. Vulnerabilidades

### 3.1 Públicas (CVE)

No hay ninguna CVE publicada sobre el almacén de password alias en sí. Sí hay CVE recientes de GlassFish que **llevan a tomar el control del dominio**, y desde ahí a los secretos: con ejecución de código como el usuario del servicio, el atacante lee los ficheros y la master password de la memoria del proceso.

| CVE | Qué permite | Versiones afectadas | Corregida en |
|---|---|---|---|
| **CVE-2026-12605** (CVSS 9.6) | CSRF + SSRF en el `DownloadServlet` de la consola: filtra el token de administración (`gfresttoken`) con un administrador conectado. Toma del dominio | 8.0.0 – 8.0.3 | **8.0.4** |
| **CVE-2026-2586** (CVSS 9.1) | Ejecución remota de código desde la consola de administración (requiere acceso de administrador) | 8.0.0 – 8.0.1; 7.0.0 – 7.0.25; 7.1.0 | **8.0.2**, 7.0.26, 7.1.1 |
| CVE-2024-9329, CVE-2024-9343, CVE-2024-10031 | Redirección abierta y XSS almacenado en la administración | 7.0.x | 7.0.17 y posteriores (según la fuente) |
| CVE-2017-1000030 | Revelación de la contraseña del keystore (GlassFish 3.0.1, Oracle) | 3.0.1 | Histórica |

Los datos de las CVE de 2026 proceden de bases de datos agregadas (NVD, GitLab Advisory, OpenCVE, Mend). Este entorno no tiene acceso a NVD ni a OSV, así que conviene confirmarlos en el aviso oficial de Eclipse antes de usarlos en un informe formal.

### 3.2 Defectos encontrados en esta revisión (no publicados)

| Id | Defecto | Evidencia | Impacto |
|---|---|---|---|
| **S-1** | `create-domain` **descarta la master password recibida por `--passwordfile`** (`AS_ADMIN_MASTERPASSWORD`) y crea el dominio con `changeit`, **sin avisar**. Solo funciona si se teclea de forma interactiva | `CreateDomainCommand` (líneas 270-280 en `main`): la rama `else` asigna `KEYSTORE_PASSWORD_DEFAULT`. Reproducido: sin prompt, el `keystore.p12` resultante se abre con `changeit`; en modo interactivo, con la contraseña fuerte | **Alto.** Toda creación automatizada (scripts, Docker, CI) que "pone" una master password fuerte deja en realidad `changeit`, con todos los alias descifrables desde una copia del fichero |
| **S-2** | Tras `create/update/delete-password-alias`, **`domain-passwords.p12` pierde los permisos `600`** y queda con los del `umask` del proceso (`644` con el habitual `022`) | `create-domain` hace `chmod 600`, pero `PasswordAdapter.writeKeyStoreSafe` renombra el fichero y crea uno nuevo sin restringirlo. Reproducido: `-rw-------` → `-rw-r--r--` | **Medio.** Cualquier usuario local puede copiar el almacén. Combinado con S-1 o con la master password por defecto, puede leer todos los secretos |
| **S-3** | `update-password-alias` declara `@ExecuteOn(DAS)`, mientras que `create` y `delete` declaran `@ExecuteOn(ALL)` | Código fuente | Bajo, por confirmar en clúster: la rotación podría no propagarse a las instancias |

**Divulgación responsable:** S-1 y S-2 son defectos de seguridad. Se recomienda **no** abrir un issue público, sino comunicarlos de forma privada al equipo de seguridad de la Eclipse Foundation (o mediante un aviso privado de GitHub en `eclipse-ee4j/glassfish`), según la política de vulnerabilidades de la Eclipse Foundation. S-3 puede ir como issue normal una vez confirmado.

## 4. Endurecimiento (verificado en GlassFish 8.0.5)

| # | Medida | Cómo | Verificado |
|---|---|---|---|
| 1 | **Master password fuerte**: 20 o más caracteres aleatorios, nunca `changeit` | Para automatizar, crear el dominio y **después** cambiarla, con el dominio parado: `asadmin --passwordfile cm.txt change-master-password --savemasterpassword=false <dominio>`, con `AS_ADMIN_MASTERPASSWORD=changeit` y `AS_ADMIN_NEWMASTERPASSWORD=<fuerte>` en `cm.txt` (rodea S-1) | ✅ Tras el cambio, `changeit` ya no abre el keystore y la nueva sí |
| 2 | **No guardar la master password** (`--savemasterpassword=false`) | Darla en cada arranque: `asadmin --passwordfile st.txt start-domain` con `AS_ADMIN_MASTERPASSWORD=…` en un fichero temporal (tmpfs, `600`) que se genera desde el gestor de secretos y se borra tras arrancar | ✅ El dominio arranca sin `master-password.p12` |
| 3 | **`umask 077`** para el proceso de GlassFish (en systemd, `UMask=0077`) y `chmod 600` del almacén tras cada cambio | Rodea S-2 | ✅ Con `umask 077`, tras `create-password-alias` el fichero sigue en `600` |
| 4 | **Usuario de sistema dedicado** sin shell; el directorio del dominio solo accesible por él | Permisos del SO | — |
| 5 | **GlassFish ≥ 8.0.4** (CVE-2026-12605 y CVE-2026-2586) y consola de administración no expuesta a Internet | `asadmin disable-secure-admin` / red de gestión separada | ✅ La PoC usa 8.0.5 |
| 6 | **Copias de seguridad cifradas**: un backup del dominio contiene el almacén, y si incluye `master-password.p12`, también la llave | Cifrar los backups y guardar la master password en otro sitio (gestor de secretos) | — |
| 7 | **Monitorizar la integridad** de `domain-passwords.p12` y `master-password.p12`, y alertar ante lecturas inesperadas | auditd / FIM | — |
| 8 | **Rotar** los secretos de aplicación si el almacén pudo copiarse en algún momento con la master password por defecto | `SecretStore.store` o `update-password-alias` | — |

Comprobación automática: `runtime/check-security.sh` (solo lectura) revisa los puntos 1, 2, 3 y 5 y falla si alguno no se cumple. Sobre el dominio `domain1` de la PoC detecta, correctamente, la master password por defecto y los permisos `644`.

## 5. Implicaciones para Atlas (ADR-008)

- El adaptador `SecretStore` **no debilita** el almacén: escribe con los comandos oficiales y nunca expone los valores. Pero tampoco puede ser **más seguro que la master password**.
- Antes de producción, `check-security.sh` debe pasar sin fallos, como requisito de despliegue.
- Para secretos de alto valor, o si no se puede garantizar el endurecimiento, el contrato permite cambiar el almacén sin tocar las aplicaciones: un plugin `SecretStore` sobre un gestor externo (Vault, KMS) con mayor `service.ranking`.
