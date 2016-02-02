package de.thokari.vertx.registration

import io.vertx.groovy.ext.web.Router
import io.vertx.groovy.ext.web.handler.BodyHandler
import io.vertx.groovy.ext.auth.jwt.JWTAuth
import io.vertx.groovy.ext.web.handler.JWTAuthHandler
import io.vertx.groovy.ext.web.handler.StaticHandler

def router = Router.router(vertx)

// Create a JWT Auth Provider
def jwtAuth = JWTAuth.create(vertx, [
    keyStore: [
        type: 'jceks',
        path: 'keystore.jceks',
        password: 'password'
    ]
])

def eb = vertx.eventBus()

// parse request body
router.post('/api/*').handler(BodyHandler.create())

// this route is excluded from the auth handler (it represents your login endpoint)
router.post('/api/login').handler({ ctx ->
    def params = ctx.getBodyAsJson()
    def msg = [
        email: params.email,
        password: params.password,
        authorities: params.authorities
    ]
    ctx.response().putHeader('Content-Type', 'text/plain')
    eb.send('registration.login', msg, { res ->
        if (res.succeeded()) {
            ctx.response().end(res.result().body().token)
        } else {
            ctx.response().end(res.cause().message)
        }
    })
})

// protect the API (any authority is allowed)
router.route('/api/protected').handler(JWTAuthHandler.create(jwtAuth))

router.get("/api/protected").handler({ ctx ->
  ctx.response().putHeader("Content-Type", "text/plain")
  ctx.response().end("this secret is not defcon!")
})

// protect the API (defcon1 authority is required)
router.route("/api/protected/defcon1").handler(JWTAuthHandler.create(jwt).addAuthority("defcon1"))

router.get("/api/protected/defcon1").handler({ ctx ->
  ctx.response().putHeader("Content-Type", "text/plain")
  ctx.response().end("this secret is defcon1!")
})

// protect the API (defcon2 authority is required)
router.route("/api/protected/defcon2").handler(JWTAuthHandler.create(jwt).addAuthority("defcon2"))

router.get("/api/protected/defcon2").handler({ ctx ->
  ctx.response().putHeader("Content-Type", "text/plain")
  ctx.response().end("this secret is defcon2!")
})

// protect the API (defcon3 authority is required)
router.route("/api/protected/defcon3").handler(JWTAuthHandler.create(jwt).addAuthority("defcon3"))

router.get("/api/protected/defcon3").handler({ ctx ->
  ctx.response().putHeader("Content-Type", "text/plain")
  ctx.response().end("this secret is defcon3!")
})

// Serve the non private static pages
router.route().handler(StaticHandler.create().setWebRoot('public'))

vertx.createHttpServer().requestHandler(router.&accept).listen(8080)
