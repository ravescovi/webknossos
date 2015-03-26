### define
underscore : _
backbone : Backbone
../pagination_collection : PaginationCollection
###

class UserCollection extends PaginationCollection

  url : "/api/users"

  paginator_ui :
    perPage : 50
