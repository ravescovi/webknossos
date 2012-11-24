package controllers.admin

import brainflight.security.Secured
import models.security.Role
import controllers.Controller
import models.tracing.Tracing
import views._
import models.user.User
import play.api.data._
import play.api.data.Forms._
import controllers.Application
import brainflight.mail.Send
import brainflight.mail.DefaultMails

object TrainingsTracingAdministration extends Controller with Secured {
  val DefaultRole = Role.Admin

  val reviewForm = Form(
    single(
      "comment" -> text))

  def startReview(training: String) = Authenticated { implicit request =>
    (for {
      tracing <- Tracing.findOneById(training)
      if (tracing.state.isReadyForReview)
      altered <- Tracing.assignReviewee(tracing, request.user)
    } yield {
      AjaxOk.success(
        html.admin.task.trainingsTasksDetailTableItem(request.user, altered),
        "You got assigned as reviewee.")
    }) getOrElse BadRequest("Trainings-Tracing not found.")
  }

  def oxalisReview(training: String) = Authenticated { implicit request =>
    (for {
      tracing <- Tracing.findOneById(training)
      review <- tracing.review.headOption
    } yield {
      Redirect(controllers.routes.Game.trace(review.reviewTracing.toString))
    }) getOrElse BadRequest("Couldn't create review tracing.")
  }

  def abortReview(trainingsId: String) = Authenticated { implicit request =>
    Tracing.findOneById(trainingsId) map { training =>
      val altered = training.update(_.unassignReviewer)
      AjaxOk.success(
        html.admin.task.trainingsTasksDetailTableItem(request.user, altered),
        "You got unassigned from this training.")
    } getOrElse BadRequest("Trainings-Tracing not found.")
  }

  def finishReview(training: String) = Authenticated { implicit request =>
    Tracing.findOneById(training) map { tracing =>
      tracing.review match {
        case r :: _ if r._reviewee == request.user._id && tracing.state.isInReview =>
          Ok(html.admin.task.trainingsReview(tracing, reviewForm))
        case _ =>
          BadRequest("No open review found.")
      }
    } getOrElse BadRequest("Trainings-Tracing not found.")
  }

  def finishReviewForm(training: String, passed: Boolean) = Authenticated(parser = parse.urlFormEncoded) { implicit request =>
    reviewForm.bindFromRequest.fold(
      formWithErrors =>
        BadRequest,
      { comment =>
        (for {
          tracing <- Tracing.findOneById(training)
          if tracing.state.isInReview
          review <- tracing.review.headOption
          if review._reviewee == request.user._id
          task <- tracing.task
          training <- task.training
          trainee <- tracing.user
        } yield {
          if (passed) {
            trainee.update(_.addExperience(training.domain, training.gain))
            tracing.update(_.finishReview(comment).finish)
            Application.Mailer ! Send(
              DefaultMails.trainingsSuccessMail(trainee.name, trainee.email, comment))
          } else {
            tracing.update(_.finishReview(comment).reopen)
            Application.Mailer ! Send(
              DefaultMails.trainingsFailureMail(trainee.name, trainee.email, comment))
          }
          Tracing.findOneById(review.reviewTracing).map( reviewTracing =>
            reviewTracing.update(_.finish))
          AjaxOk.success("Trainings review finished.")
        }) getOrElse AjaxBadRequest.error("Trainings-Tracing not found.")
      })
  }
}