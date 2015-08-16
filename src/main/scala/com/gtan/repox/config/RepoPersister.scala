package com.gtan.repox.config

import com.gtan.repox.SerializationSupport
import com.gtan.repox.admin.RepoVO
import com.gtan.repox.data.Repo
import play.api.libs.json.{JsValue, Json}

trait RepoPersister {

  case class NewRepo(vo: RepoVO) extends Cmd {
    override def transform(old: Config) = {
      val oldRepos = old.repos
      val oldConnectorUsage = old.connectorUsage
      // ToDo: validation
      val voWithId = vo.copy(repo = vo.repo.copy(id = Some(Repo.nextId.incrementAndGet())))
      val insertPoint = oldRepos.indexWhere(_.priority > vo.repo.priority)
      val newRepos = if (insertPoint == -1) {
        // put to the last
        old.copy(repos = oldRepos :+ voWithId.repo)
      } else {
        val (before, after) = oldRepos.splitAt(insertPoint)
        old.copy(repos = (before :+ voWithId.repo) ++ after)
      }
      vo.connector match {
        case None => newRepos
        case Some(p) => newRepos.copy(connectorUsage = oldConnectorUsage.updated(voWithId.repo, p))
      }
    }
  }

  object NewRepo {
    implicit val format = Json.format[NewRepo]
  }

  case class DisableRepo(id: Long) extends Cmd {
    override def transform(old: Config) = {
      val oldRepos = old.repos
      old.copy(repos = oldRepos.map {
        case o@Repo(Some(`id`), _, _, _, _, _, _) => o.copy(disabled = true)
        case o => o
      })
    }
  }

  object DisableRepo {
    implicit val format = Json.format[DisableRepo]
  }

  case class EnableRepo(id: Long) extends Cmd {
    override def transform(old: Config) = {
      val oldRepos = old.repos
      old.copy(repos = oldRepos.map {
        case o@Repo(Some(`id`), _, _, _, _, _, _) => o.copy(disabled = false)
        case o => o
      })
    }
  }

  object EnableRepo {
    implicit val format = Json.format[EnableRepo]
  }

  case class DeleteRepo(id: Long) extends Cmd {
    override def transform(old: Config) = {
      val oldRepos = old.repos
      val oldProxyUsage = old.connectorUsage
      old.copy(
        repos = oldRepos.filterNot(_.id.contains(id)),
        connectorUsage = oldProxyUsage.filterNot { case (repo, proxy) => repo.id.contains(id)}
      )
    }
  }

  object DeleteRepo {
    implicit val format = Json.format[DeleteRepo]
  }

  case class UpdateRepo(vo: RepoVO) extends Cmd {
    override def transform(old: Config) = {
      val oldRepos = old.repos
      val oldConnectorUsage = old.connectorUsage

      val newConfig = for (found <- oldRepos.find(_.id == vo.repo.id)) yield {
        val indexOfTarget = oldRepos.indexOf(found)
        val repoUpdated: Config = old.copy(repos = oldRepos.updated(indexOfTarget, vo.repo))
        (oldConnectorUsage.get(vo.repo), vo.connector) match {
          case (None, None) => repoUpdated
          case (None, Some(p)) => repoUpdated.copy(connectorUsage = oldConnectorUsage.updated(vo.repo, p))
          case (Some(p), None) => repoUpdated.copy(connectorUsage = oldConnectorUsage - vo.repo)
          case (Some(o), Some(n)) if o == n => repoUpdated
          case (Some(o), Some(n)) => repoUpdated.copy(connectorUsage = oldConnectorUsage.updated(vo.repo, n))
        }
      }
      newConfig.getOrElse(old)
    }
  }

  object UpdateRepo {
    implicit val format = Json.format[UpdateRepo]
  }

  case class MoveUpRepo(id: Long) extends Cmd {
    override def transform(old: Config) = {
      val oldRepos = old.repos
      val repo = oldRepos.find(_.id.contains(id))
      repo.fold(old) { _repo =>
        val index = oldRepos.indexOf(_repo)
        if (index == 0) {
          if (_repo.priority == 1) old // no higher level
          else old.copy(
            repos = oldRepos.map {
              case `_repo` => _repo.copy(priority = _repo.priority - 1)
              case r => r
            })
        } else {
          val previous = oldRepos(index - 1)
          if (previous.priority == _repo.priority) {
            // swap this two
            old.copy(
              repos = oldRepos.map {
                case `previous` => _repo
                case `_repo` => previous
                case r => r
              }
            )
          } else {
            // if(previous.priority == _repo.priority - 1)  uplevel as last
            // if(previous.priority < _repo.priority - 1)  uplevel as the only one
            old.copy(
              repos = oldRepos.map {
                case `_repo` => _repo.copy(priority = _repo.priority - 1)
                case r => r
              }
            )
          }
        }
      }
    }
  }

  object MoveUpRepo {
    implicit val format = Json.format[MoveUpRepo]
  }

  case class MoveDownRepo(id: Long) extends Cmd {
    override def transform(old: Config) = {
      val oldRepos = old.repos
      val repo = oldRepos.find(_.id.contains(id))
      repo.fold(old) { _repo =>
        val index = oldRepos.indexOf(_repo)
        if (index == oldRepos.length - 1) {
          if (_repo.priority == 10) old // no lower priority
          else old.copy(
            repos = oldRepos.map {
              case `_repo` => _repo.copy(priority = _repo.priority + 1)
              case r => r
            }
          )
        } else {
          val next = oldRepos(index + 1)
          if (next.priority == _repo.priority) {
            // swap this two
            old.copy(
              repos = oldRepos.map {
                case `next` => _repo
                case `_repo` => next
                case r => r
              }
            )
          } else {
            // if(next.priority == _repo.priority + 1)  downlevel as first
            // if(next.priority > _repo.priority + 1)  downlevel as the only one
            old.copy(
              repos = oldRepos.map {
                case `_repo` => _repo.copy(priority = _repo.priority + 1)
                case r => r
              }
            )
          }
        }
      }
    }
  }

  object MoveDownRepo {
    implicit val format = Json.format[MoveDownRepo]
  }

}

object RepoPersister extends SerializationSupport {

  import ConfigPersister._

  val NewRepoClass      = classOf[NewRepo].getName
  val DisableRepoClass  = classOf[DisableRepo].getName
  val EnableRepoClass   = classOf[EnableRepo].getName
  val DeleteRepoClass   = classOf[DeleteRepo].getName
  val UpdateRepoClass   = classOf[UpdateRepo].getName
  val MoveUpRepoClass   = classOf[MoveUpRepo].getName
  val MoveDownRepoClass = classOf[MoveDownRepo].getName

  override val reader: JsValue => PartialFunction[String, Cmd] = payload => {
    case NewRepoClass => payload.as[NewRepo]
    case DisableRepoClass => payload.as[DisableRepo]
    case EnableRepoClass => payload.as[EnableRepo]
    case DeleteRepoClass => payload.as[DeleteRepo]
    case UpdateRepoClass => payload.as[UpdateRepo]
    case MoveUpRepoClass => payload.as[MoveUpRepo]
    case MoveDownRepoClass => payload.as[MoveDownRepo]
  }

  override val writer: PartialFunction[Cmd, JsValue] = {
    case o: NewRepo => Json.toJson(o)
    case o: DisableRepo => Json.toJson(o)
    case o: EnableRepo => Json.toJson(o)
    case o: DeleteRepo => Json.toJson(o)
    case o: UpdateRepo => Json.toJson(o)
    case o: MoveUpRepo => Json.toJson(o)
    case o: MoveDownRepo => Json.toJson(o)
  }
}