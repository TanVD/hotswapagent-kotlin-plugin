package org.hotswap.agent.plugin.kotlin;

import org.hotswap.agent.annotation.LoadEvent;
import org.hotswap.agent.annotation.OnClassLoadEvent;
import org.hotswap.agent.annotation.Plugin;
import org.hotswap.agent.config.PluginManager;
import org.hotswap.agent.javassist.CannotCompileException;
import org.hotswap.agent.javassist.CtClass;
import org.hotswap.agent.javassist.CtConstructor;
import org.hotswap.agent.javassist.CtMethod;
import org.hotswap.agent.javassist.Modifier;
import org.hotswap.agent.javassist.expr.ExprEditor;
import org.hotswap.agent.javassist.expr.FieldAccess;
import org.hotswap.agent.logging.AgentLogger;

import java.lang.reflect.Method;

@Plugin(name = "Kotlin", description = "Kotlin language support", testedVersions = {"1.3.0"}, expectedVersions = {"1.3.+"})
public class KotlinPlugin {
    private static AgentLogger LOGGER = AgentLogger.getLogger(KotlinPlugin.class);

    private static final String HOTSWAP_AGENT_CLINIT_METHOD = "__ha_clinit";

    private static boolean isNotKotlin(CtClass klass) {
        return !klass.hasAnnotation("kotlin.Metadata");
    }

    @OnClassLoadEvent(classNameRegexp = ".*", events = LoadEvent.REDEFINE)
    public static void reinitializeSingletones(final CtClass ctClass, final ClassLoader classLoader, final Class<?> originalClass) {
        try {
            if (isNotKotlin(ctClass)) {
                return;
            }
            LOGGER.debug("Class {} is subject to Kotlin plugin introspection cause it is Kotlin object", originalClass.getName());

            reinitializeStaticFields(ctClass, classLoader, originalClass);

        } catch (Exception e) {
            LOGGER.error("Exception occurred in Kotlin plugin", e);
        }
    }

    private static void reinitializeStaticFields(final CtClass ctClass, final ClassLoader classLoader,
                                                 final Class<?> originalClass) throws CannotCompileException {
        final String className = ctClass.getName();

        try {
            CtMethod origMethod = ctClass.getDeclaredMethod(HOTSWAP_AGENT_CLINIT_METHOD);
            ctClass.removeMethod(origMethod);
        } catch (org.hotswap.agent.javassist.NotFoundException ex) {
            LOGGER.debug("Class {} does not contains method {}", ex, ctClass.getName(), HOTSWAP_AGENT_CLINIT_METHOD);
            // swallow
        }

        CtConstructor clinit = ctClass.getClassInitializer();

        if (clinit != null) {
            LOGGER.debug("Adding __ha_clinit to class: {}", className);

            //TODO-tanvd should not reinitialize if bytecode doesn't changed
            CtConstructor haClinit = new CtConstructor(clinit, ctClass, null);
            haClinit.getMethodInfo().setName(HOTSWAP_AGENT_CLINIT_METHOD);
            haClinit.setModifiers(Modifier.PUBLIC | Modifier.STATIC);
            ctClass.addConstructor(haClinit);

            final boolean[] reinitializeStatics = new boolean[]{false};

            haClinit.instrument(
                    new ExprEditor() {
                        public void edit(FieldAccess f) {
                            LOGGER.debug("Looking at field {} in class {}", f.getFieldName(), ctClass.getName());
                            if (!reinitializeStatics[0] && f.isStatic() && f.isWriter()) {
                                boolean isSingletone = true;
                                try {
                                    originalClass.getDeclaredField("INSTANCE");
                                } catch (NoSuchFieldException e) {
                                    isSingletone = false;
                                }
                                if (isSingletone) {
                                    reinitializeStatics[0] = true;
                                    LOGGER.debug("Reinitializing field {} cause class {} is a Kotlin object", f.getFieldName(), ctClass.getName());
                                }
                            } else {
                                LOGGER.trace("Field {} in class {} is not static or written", f.getFieldName(), ctClass.getName());
                            }
                        }
                    }
            );

            if (reinitializeStatics[0]) {
                LOGGER.debug("Class {} will reinitialized cause it is Kotlin object with redefined class", className);
                PluginManager.getInstance().getScheduler().scheduleCommand(() -> {
                    try {
                        LOGGER.debug("Reinitializing Kotlin object {}", className);
                        Class<?> clazz = classLoader.loadClass(className);
                        Method m = clazz.getDeclaredMethod(HOTSWAP_AGENT_CLINIT_METHOD);
                        if (m != null) {
                            LOGGER.debug("Executing method {} of Kotlin object {}", m.getName(), className);
                            m.setAccessible(true);
                            m.invoke(null);
                        }
                    } catch (NoSuchMethodException ex) {
                        LOGGER.debug("Class {} does not contain method {}", ex, ctClass.getName(), HOTSWAP_AGENT_CLINIT_METHOD);
                        // swallow
                    } catch (Exception e) {
                        LOGGER.error("Error reinitializing Kotlin object {}", e, className);
                    }
                }, 150);
                // Hack (from ClassInitPlugin) : init should be done after dependant class redefinition. Since the class can
                // be proxied by syntetic proxy, the class init must be scheduled after proxy redefinition.
                // Currently proxy redefinition (in ProxyPlugin) is scheduled with 100ms delay, therefore
                // the class init must be scheduled after it.
            }
        }
    }
}
