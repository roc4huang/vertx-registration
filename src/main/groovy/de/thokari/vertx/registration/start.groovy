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
                vertx.eventBus().send('registration.register', msg, { res ->
                    if (!res.succeeded()) {
                        def reply = res.result().body()
                        def token = reply.token
                    } else {
                        res.cause().printStackTrace()
                    }
                })
            }
        } else {
            res1.cause().printStackTrace()
            future.fail()
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
