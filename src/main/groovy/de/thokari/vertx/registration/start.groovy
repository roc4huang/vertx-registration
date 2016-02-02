package de.thokari.vertx.registration

import io.vertx.groovy.ext.shell.ShellService
import io.vertx.groovy.core.Future

void vertxStart(Future<Void> future) {

    def appConfig = vertx.currentContext().config()
    println "Using configuration: $appConfig"

    vertx.deployVerticle 'groovy:de.thokari.vertx.registration.registration', [ config: appConfig.registration ], { res1 ->
        if (res1.succeeded()) {
            future.complete()
            vertx.setTimer 500, {
                def msg = [
                    email: 't.hirsch@sendandstore.de',
                    password: 'secret123',
                    passwordConfirm: 'secret123'
                ]
                vertx.eventBus().send('registration.register', msg, { res2 ->
                    if (res2.succeeded()) {
                        def reply = res2.result().body()
                        println 'REG_REPLY ' + reply
                        def token = reply.token
                        def confirmMsg = [ token: token ]
                        vertx.eventBus().send('registration.confirm', confirmMsg, { res3 ->
                            println 'CONF_REPLY ' + res3.result().body()
                            def loginMsg = [ email: msg.email, password: msg.password ]
                            vertx.eventBus().send('registration.login', msg, { res4 ->
                                println 'LOGIN_REPLY ' + res4.result().body()
                                token = res4.result().body()
                            })
                        })
                    } else {
                        res2.cause().printStackTrace()
                    }
                })
            }
        } else {
            res1.cause().printStackTrace()
            future.fail()
        }
    }

    vertx.deployVerticle 'groovy:de.thokari.vertx.registration.httpEndpoint', [ config: appConfig.httpEndpoint ], { res2 ->
        if (!res2.succeeded()) {
            res2.cause().printStackTrace()
        }
    }

    def service = ShellService.create vertx, [
        telnetOptions: [
            host: 'localhost',
            port: 4000
        ]
    ]
    service.start()
}
